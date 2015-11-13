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
 * + Maintain buffers between reads if possible.  Reduces probably
 * of buffer allocation over time.
 * 
 * + Share byte buffer with RegionFile for efficiency.
 */
public class ChunkInputStream extends DataInputStream {

	// Size limit of the buffer that is kept around. Defaults
	// to 16 sectors, and may need to be tuned based on modpack
	// behaviors.
	private static final int DEFAULT_BUFFER_SIZE = 4096 * 16;

	private static final int CHUNK_HEADER_SIZE = 5;

	private final static ConcurrentLinkedQueue<ChunkInputStream> freeInputStreams = new ConcurrentLinkedQueue<ChunkInputStream>();

	public static ChunkInputStream getStream() {
		final ChunkInputStream buffer = freeInputStreams.poll();
		return buffer != null ? buffer : new ChunkInputStream();
	}

	public static void returnStream(final ChunkInputStream stream) throws IOException {
		// Ensure the stream is closed.  It will
		// be placed on the free list.
		if (stream != null)
			stream.close();
	}

	private byte[] inputBuffer;
	private Inflater inflater;
	private ByteArrayInputStreamNonAsync input;
	private InflaterInputStream inflaterStream;

	public ChunkInputStream() {
		super(null);

		inputBuffer = new byte[DEFAULT_BUFFER_SIZE];
		input = new ByteArrayInputStreamNonAsync();
		inflater = new Inflater();
	}

	public ChunkInputStream bake() {
		if (inputBuffer == null)
			inputBuffer = new byte[DEFAULT_BUFFER_SIZE];

		input.attach(inputBuffer, CHUNK_HEADER_SIZE, inputBuffer.length);
		inflater.reset();
		inflaterStream = new InflaterInputStream(input, inflater);
		in = inflaterStream;
		return this;
	}

	public byte[] getBuffer() {
		return inputBuffer;
	}

	/**
	 * Get's the buffer associated with the stream and ensures it is of
	 * an appropriate size.  The length of the buffer returned can be
	 * greater than requested.
	 * 
	 * @param desiredSize
	 * @return
	 */
	public byte[] getBuffer(final int desiredSize) {
		if (inputBuffer == null || desiredSize > inputBuffer.length)
			inputBuffer = new byte[Math.max(desiredSize, DEFAULT_BUFFER_SIZE)];

		return inputBuffer;
	}

	/**
	 * Attaches the buffer to this stream. The stream takes ownership.
	 * 
	 * @param buffer
	 * @return
	 */
	public byte[] setBuffer(final byte[] buffer) {
		inputBuffer = buffer;
		return buffer;
	}

	@Override
	public void close() throws IOException {
		// Close out the underlying stream and queue
		// for reuse.
		if (in != null) {
			in.close();
			in = null;
			inflaterStream = null;
		}

		// To the free list!
		freeInputStreams.add(this);
	}
}
