//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.thread;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.waitAtMost;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReservedThreadExecutorTest
{
    private static final int SIZE = 2;
    private static final Runnable NOOP = new Runnable()
    {
        @Override
        public void run()
        {
        }

        @Override
        public String toString()
        {
            return "NOOP!";
        }
    };

    private TestExecutor _executor;
    private ReservedThreadExecutor _reservedExecutor;

    @BeforeEach
    public void before() throws Exception
    {
        System.gc();
        _executor = new TestExecutor();
        _reservedExecutor = new ReservedThreadExecutor(_executor, SIZE);
        _reservedExecutor.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _reservedExecutor.stop();
    }

    @Test
    public void testStarted()
    {
        // Reserved threads are lazily started.
        assertThat(_executor._queue.size(), is(0));
    }

    @Test
    public void testInterruptedFlagCleared()
    {
        // Prime the reserved executor.
        for (int i = 0; i < SIZE; i++)
        {
            assertFalse(_reservedExecutor.tryExecute(NOOP));
        }
        for (int i = 0; i < SIZE; i++)
        {
            _executor.startThread();
        }
        assertThat(_executor._queue.size(), is(0));
        waitAtMost(10, SECONDS).until(_reservedExecutor::getAvailable, is(SIZE));

        // Execute tasks that leave the interrupted flag to true.
        for (int i = 0; i < SIZE; i++)
        {
            assertTrue(_reservedExecutor.tryExecute(() -> Thread.currentThread().interrupt()));
        }
        waitAtMost(10, SECONDS).until(_reservedExecutor::getAvailable, is(SIZE));

        // Check that the interrupted flag was cleared.
        List<Boolean> interruptedFlags = new CopyOnWriteArrayList<>();
        for (int i = 0; i < SIZE; i++)
        {
            assertTrue(_reservedExecutor.tryExecute(() ->
            {
                boolean interrupted = Thread.interrupted();
                interruptedFlags.add(interrupted);
            }));
        }
        waitAtMost(10, SECONDS).until(_reservedExecutor::getAvailable, is(SIZE));

        assertThat(interruptedFlags.size(), is(SIZE));
        assertThat(interruptedFlags.stream().allMatch(interrupted -> interrupted == false), is(true));
    }

    @Test
    public void testExecuted() throws Exception
    {
        assertThat(_executor._queue.size(), is(0));

        for (int i = 0; i < SIZE; i++)
        {
            // No reserved thread available, so task should be executed and a reserve thread started
            assertFalse(_reservedExecutor.tryExecute(NOOP));
        }
        assertThat(_executor._queue.size(), is(SIZE));

        for (int i = 0; i < SIZE; i++)
        {
            // start executor threads, which should be 2 reserved thread jobs
            _executor.startThread();
        }
        assertThat(_executor._queue.size(), is(0));

        // check that the reserved thread pool grows to 2 threads
        waitAtMost(10, SECONDS).until(_reservedExecutor::getAvailable, is(SIZE));

        Task[] tasks = new Task[SIZE];
        for (int i = 0; i < SIZE; i++)
        {
            tasks[i] = new Task();
            // submit a job that will take a reserved thread.
            assertThat(_reservedExecutor.tryExecute(tasks[i]), is(true));
        }

        for (int i = 0; i < SIZE; i++)
        {
            // wait for the job to run
            tasks[i]._ran.await(10, SECONDS);
        }

        // This RTP only starts new reserved threads when it there is a miss
        assertThat(_executor._queue.size(), is(0));

        // and we have no reserved threads
        assertThat(_reservedExecutor.getAvailable(), is(0));

        // Complete the jobs

        for (int i = 0; i < SIZE; i++)
        {
            // wait for the job to run
            tasks[i]._complete.countDown();
        }

        // reserved threads should run job and then become reserved again
        waitAtMost(10, SECONDS).until(_reservedExecutor::getAvailable, is(SIZE));
    }

    @Test
    public void testEvict() throws Exception
    {
        final long IDLE = 1000;

        _reservedExecutor.stop();
        _reservedExecutor.setIdleTimeout(IDLE, MILLISECONDS);
        _reservedExecutor.start();
        assertThat(_reservedExecutor.getAvailable(), is(0));

        assertThat(_reservedExecutor.tryExecute(NOOP), is(false));
        assertThat(_reservedExecutor.tryExecute(NOOP), is(false));

        _executor.startThread();
        _executor.startThread();

        waitAtMost(10, SECONDS).until(_reservedExecutor::getAvailable, is(2));

        int available = _reservedExecutor.getAvailable();
        assertThat(available, is(2));

        waitAtMost(5 * IDLE, MILLISECONDS).until(_reservedExecutor::getAvailable, lessThanOrEqualTo(1));
    }

    private static class TestExecutor implements Executor
    {
        private final Deque<Runnable> _queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable task)
        {
            _queue.addLast(task);
        }

        public Thread startThread()
        {
            Runnable task = _queue.pollFirst();
            if (task != null)
            {
                Thread thread = new Thread(task);
                thread.start();
                return thread;
            }
            return null;
        }
    }

    private static class Task implements Runnable
    {
        private CountDownLatch _ran = new CountDownLatch(1);
        private CountDownLatch _complete = new CountDownLatch(1);

        @Override
        public void run()
        {
            _ran.countDown();
            try
            {
                _complete.await();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        }
    }

    @Tag("stress")
    @Test
    public void stressTest() throws Exception
    {
        QueuedThreadPool pool = new QueuedThreadPool(20);
        pool.start();
        ReservedThreadExecutor reserved = new ReservedThreadExecutor(pool, 10);
        reserved.setIdleTimeout(0, null);
        reserved.start();

        final int LOOPS = 200000;
        final AtomicInteger executions = new AtomicInteger(LOOPS);
        final CountDownLatch executed = new CountDownLatch(LOOPS);
        final AtomicInteger usedReserved = new AtomicInteger(0);
        final AtomicInteger usedPool = new AtomicInteger(0);

        Runnable task = new Runnable()
        {
            public void run()
            {
                try
                {
                    while (true)
                    {
                        int loops = executions.get();
                        if (loops <= 0)
                            return;

                        if (executions.compareAndSet(loops, loops - 1))
                        {
                            if (reserved.tryExecute(this))
                            {
                                usedReserved.incrementAndGet();
                            }
                            else
                            {
                                usedPool.incrementAndGet();
                                pool.execute(this);
                            }
                            return;
                        }
                    }
                }
                finally
                {
                    executed.countDown();
                }
            }
        };

        task.run();
        task.run();
        task.run();
        task.run();
        task.run();
        task.run();
        task.run();
        task.run();

        assertTrue(executed.await(60, SECONDS));

        // ensure tryExecute is still working
        while (!reserved.tryExecute(() -> {}))
            Thread.yield();

        reserved.stop();
        pool.stop();

        assertThat(usedReserved.get(), greaterThan(0));
        assertThat(usedReserved.get() + usedPool.get(), is(LOOPS));
    }
}
