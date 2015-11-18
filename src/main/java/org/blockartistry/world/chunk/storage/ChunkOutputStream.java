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

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class provides a reusable stream object for storing chunk information.
 * It replaces the classic DataOutputStream that is normally returned by
 * RegionFile and improves on it in the following ways:
 * 
 * + Object pool for reducing pressure on GC and improving performance a tad.
 * 
 * + The deflation compression objects are reused rather than reallocating from
 * the heap. Helps mitigate impacts of memory allocation and GC when
 * realistically they can be long lived objects with tons of reuse.
 * 
 * + The deflation parameters have been adjusted to improve performance with
 * little more data size.
 *
 */
public class ChunkOutputStream extends DataOutputStream {

	private static final Logger logger = LogManager.getLogger("ChunkOutputStream");

	private static final AtomicInteger streamNumber = new AtomicInteger();
	private final static ConcurrentLinkedQueue<ChunkOutputStream> freeOutputStreams = new ConcurrentLinkedQueue<ChunkOutputStream>();

	public static ChunkOutputStream getStream(final int chunkX, final int chunkZ, final RegionFile region) {
		ChunkOutputStream buffer = freeOutputStreams.poll();
		if (buffer == null)
			buffer = new ChunkOutputStream();

		return buffer.reset(chunkX, chunkZ, region);
	}

	// Vanilla uses 5 (default) where this uses 4.  Less compression
	// but takes less time.
	private final static int COMPRESSION_LEVEL = 4;
	
	// Use default strategy.  FILTERED doesn't buy anything, and Huffman
	// results in larger stream sizes with more overhead.
	private final static int COMPRESSION_STRATEGY = Deflater.DEFAULT_STRATEGY;
	
	// Set the compression buffer size to match the size of the minimum
	// chunk stream.
	private final static int COMPRESSION_BUFFER_SIZE = RegionFile.SECTOR_SIZE * RegionFile.MIN_SECTORS_PER_CHUNK_STREAM;

	@SuppressWarnings("unused")
	private int myID = streamNumber.incrementAndGet();

	private ChunkBuffer myChunkBuffer = new ChunkBuffer(0, 0, null);
	private Deflater myDeflater = new Deflater(COMPRESSION_LEVEL);
	private DeflaterOutputStream myDeflaterOutput;

	// Time measurement stuff. Intended to work with
	// concurrent ChunkBuffer writes in the case of
	// multiple IO write threads.
	private static boolean DO_TIMINGS = true;
	private static Object sync = new Object();
	private static long bytesWritten;
	private static long rawBytesWritten;
	private static long totalWrites = -10;
	private static long outstandingWrites;
	private static long accumulatedTime;
	private static long sectorsWritten;
	private static long timeMarker;
	private static long zeroCounts;

	private ChunkOutputStream() {
		// The protected stream member will be set further down
		super(null);
		
		// Setup our buffers and deflater. These guys will be
		// reused over and over...
		this.myDeflater = new Deflater(COMPRESSION_LEVEL);
		this.myDeflater.setStrategy(COMPRESSION_STRATEGY);
		this.myChunkBuffer = new ChunkBuffer(0, 0, null);
		this.myDeflaterOutput = new DeflaterOutputStream(myChunkBuffer, myDeflater, COMPRESSION_BUFFER_SIZE);

		// Set the stream!
		this.out = this.myDeflaterOutput;
	}

	@Override
	public void close() throws IOException {
		// We don't want to close out the deflation stream. Make it
		// finish what it's doing, and then tell our chunk buffer to
		// close(). This will cause an underlying write to occur.
		// Once all that is done toss the ChunkOutputStream on the
		// free list so it can be reused.
		this.myDeflaterOutput.finish();
		this.myChunkBuffer.close();

		if (DO_TIMINGS) {
			synchronized (sync) {
				long myTotalWrites = ++totalWrites;
				outstandingWrites--;
				if(myTotalWrites > 0) {
					long myTotalBytes = (bytesWritten += myChunkBuffer.size() + RegionFile.CHUNK_STREAM_HEADER_SIZE);
					long myTotalRawBytes = (rawBytesWritten += size() + RegionFile.CHUNK_STREAM_HEADER_SIZE);
					long mySectors = (sectorsWritten += (myChunkBuffer.size() + RegionFile.CHUNK_STREAM_HEADER_SIZE + RegionFile.SECTOR_SIZE)
							/ RegionFile.SECTOR_SIZE);
					
					if(outstandingWrites == 0) {
						long myAccumTime = (accumulatedTime += (System.nanoTime() - timeMarker) / 1000);
						if(++zeroCounts % 5 == 0) {
							// adjust to msecs
							myAccumTime /= 1000;
							final float avgWriteTime = (float) myAccumTime / myTotalWrites;
							final float avgBytes = (float) myTotalRawBytes / myTotalWrites;
							final float avgSectors = (float) mySectors / myTotalWrites;
							final float throughput = (float) avgBytes / avgWriteTime;
							final int ratio = (int) (myTotalRawBytes / (myTotalBytes + 1));
							logger.info("Avg " + avgWriteTime + ", Avg size " + avgBytes + " (rate " + throughput
									+ " b/msec, writes " + myTotalWrites + "), compression " + ratio + ":1, Avg sectors "
									+ avgSectors);
							
							totalWrites = 0;
							bytesWritten = 0;
							rawBytesWritten = 0;
							sectorsWritten = 0;
							accumulatedTime = 0;
						}
					}
				}
			}
		}

		freeOutputStreams.add(this);
	}

	protected ChunkOutputStream reset(final int chunkX, final int chunkZ, final RegionFile region) {
		// Reset our ChunkBuffer and Deflater for new work.
		this.myChunkBuffer.reset(chunkX, chunkZ, region);
		this.myDeflater.reset();
		this.written = 0;

		// Getting the timeMarker initialized isn't
		// 100% perfect because of potential thread
		// switch but it is good enough.
		if (DO_TIMINGS)
			synchronized (sync) {
				if(++outstandingWrites == 1)
					timeMarker = System.nanoTime();
			}

		return this;
	}

}
