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

package org.eclipse.jetty.util.thread;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
    public void testPending() throws Exception
    {
        assertThat(_executor._queue.size(), is(0));

        for (int i = 0; i < SIZE; i++)
        {
            _reservedExecutor.tryExecute(NOOP);
        }
        assertThat(_executor._queue.size(), is(SIZE));

        for (int i = 0; i < SIZE; i++)
        {
            _executor.startThread();
        }
        assertThat(_executor._queue.size(), is(0));

        waitForAllAvailable();

        for (int i = 0; i < SIZE; i++)
        {
            assertThat(_reservedExecutor.tryExecute(new Task()), is(true));
        }
        assertThat(_executor._queue.size(), is(1));
        assertThat(_reservedExecutor.getAvailable(), is(0));

        for (int i = 0; i < SIZE; i++)
        {
            assertThat(_reservedExecutor.tryExecute(NOOP), is(false));
        }
        assertThat(_executor._queue.size(), is(SIZE));
        assertThat(_reservedExecutor.getAvailable(), is(0));
    }

    @Test
    public void testExecuted() throws Exception
    {
        assertThat(_executor._queue.size(), is(0));

        for (int i = 0; i < SIZE; i++)
        {
            _reservedExecutor.tryExecute(NOOP);
        }
        assertThat(_executor._queue.size(), is(SIZE));

        for (int i = 0; i < SIZE; i++)
        {
            _executor.startThread();
        }
        assertThat(_executor._queue.size(), is(0));

        waitForAllAvailable();

        Task[] tasks = new Task[SIZE];
        for (int i = 0; i < SIZE; i++)
        {
            tasks[i] = new Task();
            assertThat(_reservedExecutor.tryExecute(tasks[i]), is(true));
        }

        for (int i = 0; i < SIZE; i++)
        {
            tasks[i]._ran.await(10, TimeUnit.SECONDS);
        }

        assertThat(_executor._queue.size(), is(1));

        Task extra = new Task();
        assertThat(_reservedExecutor.tryExecute(extra), is(false));
        assertThat(_executor._queue.size(), is(2));

        Thread.sleep(500);
        assertThat(extra._ran.getCount(), is(1L));

        for (int i = 0; i < SIZE; i++)
        {
            tasks[i]._complete.countDown();
        }

        waitForAllAvailable();
    }

    @Test
    public void testShrink() throws Exception
    {
        final long IDLE = 1000;

        _reservedExecutor.stop();
        _reservedExecutor.setIdleTimeout(IDLE, TimeUnit.MILLISECONDS);
        _reservedExecutor.start();
        assertThat(_reservedExecutor.getAvailable(), is(0));

        assertThat(_reservedExecutor.tryExecute(NOOP), is(false));
        assertThat(_reservedExecutor.tryExecute(NOOP), is(false));

        _executor.startThread();
        _executor.startThread();

        waitForAvailable(2);

        int available = _reservedExecutor.getAvailable();
        assertThat(available, is(2));
        Thread.sleep((5 * IDLE) / 2);
        assertThat(_reservedExecutor.getAvailable(), is(0));
    }

    @Test
    public void testBusyShrink() throws Exception
    {
        final long IDLE = 1000;

        _reservedExecutor.stop();
        _reservedExecutor.setIdleTimeout(IDLE, TimeUnit.MILLISECONDS);
        _reservedExecutor.start();
        assertThat(_reservedExecutor.getAvailable(), is(0));

        assertThat(_reservedExecutor.tryExecute(NOOP), is(false));
        assertThat(_reservedExecutor.tryExecute(NOOP), is(false));

        _executor.startThread();
        _executor.startThread();

        waitForAvailable(2);

        int available = _reservedExecutor.getAvailable();
        assertThat(available, is(2));

        for (int i = 10; i-- > 0;)
        {
            assertThat(_reservedExecutor.tryExecute(NOOP), is(true));
            Thread.sleep(200);
        }
        assertThat(_reservedExecutor.getAvailable(), is(1));
    }

    @Test
    public void testReservedIdleTimeoutWithOneReservedThread() throws Exception
    {
        long idleTimeout = 500;
        _reservedExecutor.stop();
        _reservedExecutor.setIdleTimeout(idleTimeout, TimeUnit.MILLISECONDS);
        _reservedExecutor.start();

        assertThat(_reservedExecutor.tryExecute(NOOP), is(false));
        Thread thread = _executor.startThread();
        assertNotNull(thread);
        waitForAvailable(1);

        Thread.sleep(2 * idleTimeout);

        waitForAvailable(0);
        thread.join(2 * idleTimeout);
        assertFalse(thread.isAlive());
    }

    protected void waitForAvailable(int size) throws InterruptedException
    {
        long started = NanoTime.now();
        while (_reservedExecutor.getAvailable() < size)
        {
            if (NanoTime.secondsSince(started) > 10)
                fail("Took too long");
            Thread.sleep(10);
        }
        assertThat(_reservedExecutor.getAvailable(), is(size));
    }

    protected void waitForAllAvailable() throws InterruptedException
    {
        waitForAvailable(SIZE);
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

        assertTrue(executed.await(60, TimeUnit.SECONDS));

        // ensure tryExecute is still working
        while (!reserved.tryExecute(() -> {}))
            Thread.yield();

        reserved.stop();
        pool.stop();

        assertThat(usedReserved.get(), greaterThan(0));
        assertThat(usedReserved.get() + usedPool.get(), is(LOOPS));
    }
}
