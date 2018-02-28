//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util.thread;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

@ManagedObject
public class MonitoredQueuedThreadPool extends QueuedThreadPool
{
    private final LongAdder tasks = new LongAdder();
    private final AtomicLong maxTaskLatency = new AtomicLong();
    private final LongAdder totalTaskLatency = new LongAdder();
    private final MonitoringBlockingArrayQueue queue;
    private final AtomicLong maxQueueLatency = new AtomicLong();
    private final LongAdder totalQueueLatency = new LongAdder();
    private final AtomicInteger threads = new AtomicInteger();
    private final AtomicInteger maxThreads = new AtomicInteger();

    public MonitoredQueuedThreadPool()
    {
        this(256);
    }

    public MonitoredQueuedThreadPool(int maxThreads)
    {
        super(maxThreads, maxThreads, 24 * 3600 * 1000, new MonitoringBlockingArrayQueue(maxThreads, 256));
        queue = (MonitoringBlockingArrayQueue)getQueue();
        setStopTimeout(2000);
    }

    @Override
    public void execute(final Runnable job)
    {
        final long begin = System.nanoTime();
        super.execute(new Runnable()
        {
            @Override
            public void run()
            {
                long queueLatency = System.nanoTime() - begin;
                tasks.increment();
                Atomics.updateMax(maxQueueLatency, queueLatency);
                totalQueueLatency.add(queueLatency);
                Atomics.updateMax(maxThreads, threads.incrementAndGet());
                long start = System.nanoTime();
                try
                {
                    job.run();
                }
                finally
                {
                    long taskLatency = System.nanoTime() - start;
                    threads.decrementAndGet();
                    Atomics.updateMax(maxTaskLatency, taskLatency);
                    totalTaskLatency.add(taskLatency);
                }
            }

            @Override
            public String toString()
            {
                return job.toString();
            }
        });
    }

    @ManagedOperation(value = "resets the statistics", impact = "ACTION")
    public void reset()
    {
        tasks.reset();
        maxTaskLatency.set(0);
        totalTaskLatency.reset();
        queue.reset();
        maxQueueLatency.set(0);
        totalQueueLatency.reset();
        threads.set(0);
        maxThreads.set(0);
    }

    @ManagedAttribute("the number of tasks executed")
    public long getTasks()
    {
        return tasks.sum();
    }

    @ManagedAttribute("the maximum number of busy threads")
    public int getMaxBusyThreads()
    {
        return maxThreads.get();
    }

    @ManagedAttribute("the maximum task queue size")
    public int getMaxQueueSize()
    {
        return queue.maxSize.get();
    }

    @ManagedAttribute("the average time a task remains in the queue, in nanoseconds")
    public long getAverageQueueLatency()
    {
        long count = tasks.sum();
        return count == 0 ? -1 : totalQueueLatency.sum() / count;
    }

    @ManagedAttribute("the maximum time a task remains in the queue, in nanoseconds")
    public long getMaxQueueLatency()
    {
        return maxQueueLatency.get();
    }

    @ManagedAttribute("the average task execution time, in nanoseconds")
    public long getAverageTaskLatency()
    {
        long count = tasks.sum();
        return count == 0 ? -1 : totalTaskLatency.sum() / count;
    }

    @ManagedAttribute("the maximum task execution time, in nanoseconds")
    public long getMaxTaskLatency()
    {
        return maxTaskLatency.get();
    }

    public static class MonitoringBlockingArrayQueue extends BlockingArrayQueue<Runnable>
    {
        private final AtomicInteger size = new AtomicInteger();
        private final AtomicInteger maxSize = new AtomicInteger();

        public MonitoringBlockingArrayQueue(int capacity, int growBy)
        {
            super(capacity, growBy);
        }

        public void reset()
        {
            size.set(0);
            maxSize.set(0);
        }

        @Override
        public void clear()
        {
            reset();
            super.clear();
        }

        @Override
        public boolean offer(Runnable job)
        {
            boolean added = super.offer(job);
            if (added)
                increment();
            return added;
        }

        private void increment()
        {
            Atomics.updateMax(maxSize, size.incrementAndGet());
        }

        @Override
        public Runnable poll()
        {
            Runnable job = super.poll();
            if (job != null)
                decrement();
            return job;
        }

        @Override
        public Runnable poll(long time, TimeUnit unit) throws InterruptedException
        {
            Runnable job = super.poll(time, unit);
            if (job != null)
                decrement();
            return job;
        }

        @Override
        public Runnable take() throws InterruptedException
        {
            Runnable job = super.take();
            decrement();
            return job;
        }

        private void decrement()
        {
            size.decrementAndGet();
        }
    }
}
