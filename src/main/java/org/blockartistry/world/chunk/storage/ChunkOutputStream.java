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
 * This class provides a reusable stream object for storing
 * chunk information.  It replaces the classic DataOutputStream
 * that is normally returned by RegionFile and improves on it
 * in the following ways:
 * 
 * + Object pool for reducing pressure on GC and improving
 * performance a tad.
 * 
 * + The deflation compression objects are reused rather than
 * reallocating from the heap.  Helps mitigate impacts of
 * memory allocation and GC when realistically they can be
 * long lived objects with tons of reuse.
 * 
 * + The deflation parameters have been adjusted to improve
 * performance with little more data size.
 *
 */
public class ChunkOutputStream extends DataOutputStream {

	private static final Logger logger = LogManager.getLogger();

	private static final AtomicInteger streamNumber = new AtomicInteger();
	private final static ConcurrentLinkedQueue<ChunkOutputStream> freeOutputStreams = new ConcurrentLinkedQueue<ChunkOutputStream>();

	public static ChunkOutputStream getStream(final int chunkX, final int chunkZ, final RegionFile region) {
		ChunkOutputStream buffer = freeOutputStreams.poll();
		if(buffer == null)
			buffer = new ChunkOutputStream();
		
		return buffer.reset(chunkX, chunkZ, region);
	}

	// Use different compression parameters than Vanilla, which
	// uses the default settings.
	private final static int COMPRESSION_LEVEL = 3;
	private final static int COMPRESSION_STRATEGY = Deflater.FILTERED;
	private final static int COMPRESSION_BUFFER_SIZE = 4096;

	@SuppressWarnings("unused")
	private int myID = streamNumber.incrementAndGet();

	private ChunkBuffer myChunkBuffer = new ChunkBuffer(0, 0, null);
	private Deflater myDeflater = new Deflater(COMPRESSION_LEVEL);
	private DeflaterOutputStream myDeflaterOutput;
	
	// Time measurement stuff. Intended to work with
	// concurrent ChunkBuffer writes in the case of
	// multiple IO write threads.
	private final static boolean DO_TIMINGS = true;
	private final static AtomicInteger bytesWritten;
	private final static AtomicInteger rawBytesWritten;
	private final static AtomicInteger totalWrites;
	private final static AtomicInteger outstandingWrites;
	private final static AtomicInteger accumulatedTime;
	private final static AtomicInteger sectorsWritten;
	private static volatile long timeMarker = 0;

	static {
		if (DO_TIMINGS) {
			bytesWritten = new AtomicInteger();
			rawBytesWritten = new AtomicInteger();
			totalWrites = new AtomicInteger(-10); // Throw away the first 10 writes
			outstandingWrites = new AtomicInteger();
			accumulatedTime = new AtomicInteger();
			sectorsWritten = new AtomicInteger();
		} else {
			bytesWritten = null;
			rawBytesWritten = null;
			totalWrites = null;
			outstandingWrites = null;
			accumulatedTime = null;
			sectorsWritten = null;
		}
	}

	private ChunkOutputStream() {
		// The protected stream member will be set further down
		super(null);

		// Setup our buffers and deflater.  These guys will be
		// reused over and over...
		this.myDeflater = new Deflater(COMPRESSION_LEVEL);
		this.myDeflater.setStrategy(COMPRESSION_STRATEGY);
		this.myChunkBuffer = new ChunkBuffer(0, 0, null);
		this.myDeflaterOutput = new DeflaterOutputStream(myChunkBuffer, myDeflater,
				COMPRESSION_BUFFER_SIZE);
		
		// Set the stream!
		this.out = this.myDeflaterOutput;
	}
	
	@Override
	public void close() throws IOException {
		// We don't want to close out the deflation stream.  Make it
		// finish what it's doing, and then tell our chunk buffer to
		// close().  This will cause an underlying write to occur.
		// Once all that is done toss the ChunkOutputStream on the
		// free list so it can be reused.
		this.myDeflaterOutput.finish();
		this.myChunkBuffer.close();
		
		if (DO_TIMINGS) {
			int numWrites = totalWrites.incrementAndGet();
			
			if(numWrites > 0) {
				final int secs = (myChunkBuffer.size() + 5 + 4096) / 4096;
				final int totalBytes = bytesWritten.addAndGet(myChunkBuffer.size() + 5);
				final int totalRawBytes = rawBytesWritten.addAndGet(size() + 5);
				final int sectors = sectorsWritten.addAndGet(secs);

				// If it was the last one doing a write dump out
				// some stats to the console. This is not 100%
				// perfect because a thread switch could occur
				// between the outstandingWrites adjustment
				// and the accumulatedTime modification, and the
				// resulting data printed to the console could be
				// off. However, if it is the last write the
				// information printed will be correct.
				if (outstandingWrites.decrementAndGet() == 0) {
					final int accumTime = accumulatedTime.addAndGet((int) ((System.nanoTime() - timeMarker) / 1000));
	
					final float avgWriteTime = (float)accumTime / numWrites;
					final float avgBytes = (float)totalRawBytes / numWrites;
					final float avgSectors = (float)sectors / numWrites;
					final float throughput = (float)avgBytes / avgWriteTime;
					final int ratio = totalRawBytes / totalBytes;
					logger.info("Avg " + avgWriteTime + ", Avg size " + avgBytes + " (rate " + throughput
							+ " b/msec, writes " + numWrites + "), compression " + ratio + ":1, Avg sectors " + avgSectors);
				}
			} else {
				outstandingWrites.decrementAndGet();
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
			if (outstandingWrites.incrementAndGet() == 1)
				timeMarker = System.nanoTime();

		return this;
	}
	
}
