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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import net.minecraft.world.storage.IThreadedFileIO;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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
 * + Uses Java's ExecutorService patterns and futures.
 *  
 */
public class ThreadedFileIOBase {

	private static final Logger logger = LogManager.getLogger("ThreadedFileIOBase");

	// Threads number about a third the processors available to the JVM
	private final static int THREAD_COUNT = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
	private final static AtomicInteger outstandingTasks = new AtomicInteger();
	private final static ListeningExecutorService pool;
	public final static ThreadedFileIOBase threadedIOInstance;

	static {
		final ThreadFactory factory = new ThreadFactoryBuilder().setNameFormat("Storage #%d").build();
		pool = MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(THREAD_COUNT, factory));
		threadedIOInstance = new ThreadedFileIOBase();
		logger.info("Created threadpool with " + THREAD_COUNT + " threads");
	}

	// Provided for compatibility with other loaders
	// that want to use the writeNextIO() callback.
	private static class WrapperIThreadedFileIO implements Callable<Void> {

		private final IThreadedFileIO task;

		public WrapperIThreadedFileIO(final IThreadedFileIO task) {
			this.task = task;
		}

		@Override
		public Void call() throws Exception {
			this.task.writeNextIO();
			return null;
		}
	}

	public static ThreadedFileIOBase getThreadedIOInstance() {
		return threadedIOInstance;
	}

	private ThreadedFileIOBase() {
	}

	@Deprecated
	public void queueIO(final IThreadedFileIO task) throws Exception {
		if (task != null)
			queue(new WrapperIThreadedFileIO(task));
	}
	
	// Completion callback to ensure that the task counter
	// is decremented.
	private static class CompletionCallback implements FutureCallback<Object> {
		@Override
		public void onSuccess(final Object result) {
			outstandingTasks.decrementAndGet();
		}

		@Override
		public void onFailure(final Throwable t) {
			outstandingTasks.decrementAndGet();
		}
	}

	private final static CompletionCallback cc = new CompletionCallback();
	
	/**
	 * Queues a single callable for execution.
	 */
	public <T> ListenableFuture<T> queue(final Callable<T> call) throws Exception {
		outstandingTasks.incrementAndGet();
		try {
			final ListenableFuture<T> future = pool.submit(call);
			Futures.addCallback(future, cc);
			return future;
		} catch(final Exception ex) {
			outstandingTasks.decrementAndGet();
			throw ex;
		}
	}

	/**
	 * Queues one or more callables for execution.
	 */
	public <T> List<ListenableFuture<T>> queue(final Collection<? extends Callable<T>> calls) throws Exception {
		final List<ListenableFuture<T>> futures = new ArrayList<ListenableFuture<T>>(calls.size());
		for(final Callable<T> call : calls)
			futures.add(queue(call));
		return futures;
	}

	/**
	 * Waits for all outstanding tasks to be completed.
	 */
	public void waitForFinish() throws InterruptedException {
		// Wait for the work to drain from the queue
		while (outstandingTasks.get() != 0)
			Thread.sleep(10L);
	}
}
