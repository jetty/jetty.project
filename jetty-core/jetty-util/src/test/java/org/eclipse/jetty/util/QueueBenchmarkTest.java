//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

// TODO: Review - this is a HIGH_CPU, HIGH_MEMORY test that takes 20 minutes to execute.
// perhaps this should not be a normal every day testcase?
// Move to a different module? make it not a junit testcase?
@Disabled
public class QueueBenchmarkTest
{
    private static final Logger logger = LoggerFactory.getLogger(QueueBenchmarkTest.class);
    private static final Runnable ELEMENT = () ->
    {
    };
    private static final Runnable END = () ->
    {
    };

    @Test
    public void testQueues() throws Exception
    {
        int cores = ProcessorUtils.availableProcessors();
        assumeTrue(cores > 1);

        final int readers = cores / 2;
        final int writers = readers;
        final int iterations = 16 * 1024 * 1024;

        final List<Queue<Runnable>> queues = new ArrayList<>();
        queues.add(new ConcurrentLinkedQueue<>()); // JDK lock-free queue, allocating nodes
        queues.add(new ArrayBlockingQueue<>(iterations * writers)); // JDK lock-based, circular array queue
        queues.add(new BlockingArrayQueue<>(iterations * writers)); // Jetty lock-based, circular array queue

        testQueues(readers, writers, iterations, queues, false);
    }

    @Test
    public void testBlockingQueues() throws Exception
    {
        int cores = ProcessorUtils.availableProcessors();
        assumeTrue(cores > 1);

        final int readers = cores / 2;
        final int writers = readers;
        final int iterations = 16 * 1024 * 1024;

        final List<Queue<Runnable>> queues = new ArrayList<>();
        queues.add(new LinkedBlockingQueue<>());
        queues.add(new ArrayBlockingQueue<>(iterations * writers));
        queues.add(new BlockingArrayQueue<>(iterations * writers));

        testQueues(readers, writers, iterations, queues, true);
    }

    private void testQueues(final int readers, final int writers, final int iterations, List<Queue<Runnable>> queues, final boolean blocking) throws Exception
    {
        final int runs = 8;
        int threads = readers + writers;
        final CyclicBarrier barrier = new CyclicBarrier(threads + 1);

        for (final Queue<Runnable> queue : queues)
        {
            for (int r = 0; r < runs; ++r)
            {
                for (int i = 0; i < readers; ++i)
                {
                    Thread thread = new Thread()
                    {
                        @Override
                        public void run()
                        {
                            await(barrier);
                            consume(queue, writers, blocking);
                            await(barrier);
                        }
                    };
                    thread.start();
                }
                for (int i = 0; i < writers; ++i)
                {
                    Thread thread = new Thread()
                    {
                        @Override
                        public void run()
                        {
                            await(barrier);
                            produce(queue, readers, iterations);
                            await(barrier);
                        }
                    };
                    thread.start();
                }

                await(barrier);
                long begin = System.nanoTime();
                await(barrier);
                long end = System.nanoTime();
                long elapsed = TimeUnit.NANOSECONDS.toMillis(end - begin);
                logger.info("{} Readers/Writers: {}/{} => {} ms", queue.getClass().getSimpleName(), readers, writers, elapsed);
            }
        }
    }

    private static void consume(Queue<Runnable> queue, int writers, boolean blocking)
    {
        while (true)
        {
            Runnable element = blocking ? take(queue) : poll(queue);
            if (element == END)
                if (--writers == 0)
                    break;
        }
    }

    private static void produce(Queue<Runnable> queue, int readers, int iterations)
    {
        for (int i = 0; i < iterations; ++i)
        {
            append(queue, ELEMENT);
        }
        for (int i = 0; i < readers; ++i)
        {
            append(queue, END);
        }
    }

    private static void append(Queue<Runnable> queue, Runnable element)
    {
        if (!queue.offer(element))
            logger.warn("Queue {} capacity is too small", queue);
    }

    private static Runnable take(Queue<Runnable> queue)
    {
        try
        {
            return ((BlockingQueue<Runnable>)queue).take();
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }

    private static Runnable poll(Queue<Runnable> queue)
    {
        int loops = 0;
        while (true)
        {
            Runnable element = queue.poll();
            if (element != null)
                return element;
            // Busy loop
            sleepMicros(1);
            ++loops;
            if (loops % 16 == 0)
                logger.warn("Spin looping while polling empty queue: {} spins: ", loops);
        }
    }

    private static void sleepMicros(long sleep)
    {
        try
        {
            TimeUnit.MICROSECONDS.sleep(sleep);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }

    private static void await(CyclicBarrier barrier)
    {
        try
        {
            barrier.await();
        }
        catch (Exception x)
        {
            throw new RuntimeException(x);
        }
    }
}
