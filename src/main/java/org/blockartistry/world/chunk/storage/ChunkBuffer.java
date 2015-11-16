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

import java.io.IOException;
import java.io.OutputStream;

/**
 * Replaces Minecraft ChunkBuffer. It improves on Minecraft's implementation in
 * the following ways:
 * 
 * + Default allocation is 8 sectors. Most newly formed/empty chunks take one or
 * two sectors to hold NBT information. It is expected that modded chunks may
 * take more sectors.
 * 
 * + The chunk stream header is incorporated into the buffer to reduce impact on
 * the underlying write routines.
 * 
 * + Not based on ByteArrayOutputStream; removed synchronized methods because
 * the buffer is only access by a single thread during writes.
 */
public class ChunkBuffer extends OutputStream {

	private final static int DEFAULT_BUFFER_SIZE = RegionFile.SECTOR_SIZE * 8;

	private RegionFile file;
	private int chunkX;
	private int chunkZ;
	private byte[] buf;
	private int count;

	public ChunkBuffer(final int x, final int z, final RegionFile file) {
		this.file = file;
		this.chunkX = x;
		this.chunkZ = z;
		this.buf = new byte[DEFAULT_BUFFER_SIZE];
		this.count = RegionFile.CHUNK_STREAM_HEADER_SIZE;
	}

	public void reset() {
		// Leave space for the header
		this.count = RegionFile.CHUNK_STREAM_HEADER_SIZE;
	}

	public int size() {
		// The header is silent
		return this.count - RegionFile.CHUNK_STREAM_HEADER_SIZE;
	}

	private void ensureCapacity(final int minCapacity) {
		if (minCapacity - this.buf.length > 0) {
			final byte[] newBuffer = new byte[this.buf.length << 1];
			System.arraycopy(this.buf, 0, newBuffer, 0, this.count);
			this.buf = newBuffer;
		}
	}

	public void write(final int b) {
		ensureCapacity(this.count + 1);
		this.buf[this.count++] = (byte) b;
	}

	public void write(final byte[] b, final int off, final int len) {
		ensureCapacity(this.count + len);
		System.arraycopy(b, off, this.buf, this.count, len);
		this.count += len;
	}

	public void close() throws IOException {
		final int len = this.count - RegionFile.CHUNK_STREAM_HEADER_SIZE + 1;
		this.buf[0] = (byte) ((len >>> 24) & 0xFF);
		this.buf[1] = (byte) ((len >>> 16) & 0xFF);
		this.buf[2] = (byte) ((len >>> 8) & 0xFF);
		this.buf[3] = (byte) ((len >>> 0) & 0xFF);
		this.buf[4] = 2; // STREAM_VERSION_FLATION
		this.file.write(this.chunkX, this.chunkZ, this.buf, this.count);
		this.file = null;
	}

	public ChunkBuffer reset(final int chunkX, final int chunkZ, final RegionFile file) {
		this.file = file;
		this.chunkX = chunkX;
		this.chunkZ = chunkZ;
		this.reset();
		return this;
	}
}
