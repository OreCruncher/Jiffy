/* This file is part of Jiffy, licensed under the MIT License (MIT).
 *
 * Copyright (c) OreCruncher
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.blockartistry.world.chunk.storage;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Replacement for the standard DataInputStream that is returned from
 * RegionFile.  Improvements:
 * 
 * + Object pooling so the stream instances can be reused.  Helps reduce
 * GC pressures for IO as well as speed things up a tad for processing.
 * 
 * + Maintain buffers between reads if possible.  Reduces probability
 * of buffer allocation over time.
 * 
 * + Share byte buffer with RegionFile for efficiency.
 */
public class ChunkInputStream extends DataInputStream {

	// Size limit of the buffer that is kept around. Defaults
	// to 8 sectors, same as the output stream.
	private static final int DEFAULT_BUFFER_SIZE = RegionFile.SECTOR_SIZE * 8;
	private final static int COMPRESSION_BUFFER_SIZE = RegionFile.SECTOR_SIZE * RegionFile.MIN_SECTORS_PER_CHUNK_STREAM;

	private final static ConcurrentLinkedQueue<ChunkInputStream> freeInputStreams = new ConcurrentLinkedQueue<ChunkInputStream>();

	static ChunkInputStream getStream() {
		final ChunkInputStream buffer = freeInputStreams.poll();
		return buffer != null ? buffer : new ChunkInputStream();
	}

	static void returnStream(final ChunkInputStream stream) throws IOException {
		// Ensure the stream is closed.  It will
		// be placed on the free list.
		if (stream != null)
			stream.close();
	}

	private byte[] inputBuffer;
	private Inflater inflater;
	private AttachableByteArrayInputStream input;
	private InflaterInputStream inflaterStream;
	private boolean isBaked;

	public ChunkInputStream() {
		super(null);
		this.inputBuffer = new byte[DEFAULT_BUFFER_SIZE];
		this.input = new AttachableByteArrayInputStream(this.inputBuffer);
		this.inflater = new Inflater();
		this.inflaterStream = new InflaterInputStream(this.input, this.inflater, COMPRESSION_BUFFER_SIZE);
		this.in = this.inflaterStream;
		this.isBaked = false;
	}

	ChunkInputStream bake() {
		this.input.attach(this.inputBuffer, RegionFile.CHUNK_STREAM_HEADER_SIZE, this.inputBuffer.length);
		this.inflater.reset();
		this.isBaked = true;
		return this;
	}
	
	void unbake() {
		this.isBaked = true;
	}

	public boolean isBaked() {
		return this.isBaked;
	}
	
	/**
	 * Get's the buffer associated with the stream and ensures it is of
	 * an appropriate size.  The length of the buffer returned can be
	 * greater than requested.  Does not copy the current contents over -
	 * it is assumed the reason for getting the buffer is for the
	 * purpose of reading in compressed data.
	 * 
	 * @param desiredSize
	 * @return
	 */
	byte[] getBuffer(final int desiredSize) {
		if (desiredSize > this.inputBuffer.length)
			this.inputBuffer = new byte[Math.max(desiredSize, DEFAULT_BUFFER_SIZE)];

		return this.inputBuffer;
	}

	@Override
	public void close() throws IOException {
		// To the free list!
		this.isBaked = false;
		freeInputStreams.add(this);
	}
}
