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

package org.blockartistry.world.storage;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.world.ChunkCoordIntPair;

/**
 * Replacement ThreadedFileIOBase. It improves on the Vanilla version by:
 * 
 * + Efficient wait on the queue for work. No sleeps or other timers. Immediate
 * dispatch of work when it is queued.
 * 
 * + Multiple threads allocated to service. Number based on the number of
 * processors allocated to the JVM.
 * 
 * + Simple AtomicInteger for tracking work that is being performed.
 *
 * + Doesn't have the strange behavior of leaving a task in the queue when it is
 * being executed. Tasks are removed and it is up to application logic to put
 * the appropriate items into the queue for servicing. (This works with the
 * AnvilChunkLoader code.)
 * 
 */
public class ThreadedFileIOBase {

	private static final Logger logger = LogManager.getLogger("ThreadedFileIOBase");

	// Threads number about half the processors available to the JVM
	private final static int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
	private final static AtomicInteger outstandingTasks = new AtomicInteger();
	private final static ExecutorService pool;
	public final static ThreadedFileIOBase threadedIOInstance;

	static {

		pool = Executors.newFixedThreadPool(THREAD_COUNT);
		threadedIOInstance = new ThreadedFileIOBase();
		logger.info("Created threadpool with " + THREAD_COUNT + " threads");
	}

	private static class WrapperIThreadedFileIO implements Runnable {
		
		private final IThreadedFileIO task;
		private final AtomicInteger counter;
		
		public WrapperIThreadedFileIO(final IThreadedFileIO task, final AtomicInteger counter) {
			this.task = task;
			this.counter = counter;
		}

		@Override
		public void run() {
	        try {
	            this.task.writeNextIO();
	        } finally {
	            this.counter.decrementAndGet();
	        }
		}
	}
	
	private static class WrapperChunkCoordIO implements Runnable {
		
		private final IThreadedFileIO task;
		private final ChunkCoordIntPair coords;
		private final AtomicInteger counter;
		
		public WrapperChunkCoordIO(final IThreadedFileIO task, final ChunkCoordIntPair coords, final AtomicInteger counter) {
			this.task = task;
			this.coords = coords;
			this.counter = counter;
		}

		@Override
		public void run() {
			try {
				this.task.writeNextIO(this.coords);
			} finally {
				this.counter.decrementAndGet();
			}
		}
	}

	public static ThreadedFileIOBase getThreadedIOInstance() {
		return threadedIOInstance;
	}

	private ThreadedFileIOBase() {
	}

	public void queueIO(final IThreadedFileIO task) throws Exception {
		if (task != null) {
			outstandingTasks.incrementAndGet();
			try {
				pool.submit(new WrapperIThreadedFileIO(task, outstandingTasks));
			} catch (final Exception ex) {
				outstandingTasks.decrementAndGet();
				throw ex;
			}
		}
	}
	
	public void queueIO(final IThreadedFileIO task, final ChunkCoordIntPair chunkCoords) throws Exception {
		if(chunkCoords != null) {
			outstandingTasks.incrementAndGet();
			try {
				pool.submit(new WrapperChunkCoordIO(task, chunkCoords, outstandingTasks));
			} catch (final Exception ex) {
				outstandingTasks.decrementAndGet();
				throw ex;
			}
		}
	}

	public void waitForFinish() throws InterruptedException {
		// Wait for the work to drain from the queue
		while (outstandingTasks.get() != 0)
			Thread.sleep(10L);
	}
}
