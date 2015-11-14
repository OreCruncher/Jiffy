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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import net.minecraft.world.storage.IThreadedFileIO;

/**
 * Replacement ThreadedFileIOBase. It improves on the Vanilla version by:
 * 
 * + Uses a LinkedBlockingDeque for queuing tasks to be executed.
 * 
 * + Capable of having multiple threads servicing the queue.
 * 
 * + Efficient wait on the queue for work. No sleeps or other timers. Immediate
 * dispatch of work when it is queued.
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

    private final static int THREAD_COUNT = 3;
    private final static int THREAD_PRIORITY = Thread.NORM_PRIORITY;

    /*
    private static class Factory implements ThreadFactory {

        private final String prefix;
        private int counter = 0;

        public Factory(final String threadPrefix) {
            prefix = threadPrefix;
        }

        @Override
        public Thread newThread(final Runnable r) {
            final String name = new StringBuilder().append(prefix).append(" #").append(++counter).toString();
            final Thread thread = new Thread(r, name);
            thread.setPriority(THREAD_PRIORITY);
            thread.setDaemon(true);
            return thread;
        }
    }
     */
    
    private final static AtomicInteger outstandingTasks = new AtomicInteger();
    private final static LinkedBlockingDeque<Runnable> workQ = new LinkedBlockingDeque<Runnable>();
    private final static ExecutorService pool = new ThreadPoolExecutor(THREAD_COUNT, THREAD_COUNT, 0L,
            TimeUnit.MILLISECONDS, workQ); //, new Factory("File IO"));

    public final static ThreadedFileIOBase threadedIOInstance = new ThreadedFileIOBase();

    public static ThreadedFileIOBase getThreadedIOInstance() {
        return threadedIOInstance;
    }
    
    private ThreadedFileIOBase() {
    }

	public void queueIO(final IThreadedFileIO task) throws Exception {
        if(task != null) {
            outstandingTasks.incrementAndGet();
            try {
                pool.submit(new IThreadedFileIOWrapper(task, outstandingTasks));
            } catch(final Exception ex) {
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
