//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class ReservedThreadExecutorTest
{
    private static final int SIZE = 2;
    private static final Runnable NOOP = new Runnable()
    {
        @Override
        public void run()
        {}

        @Override
        public String toString()
        {
            return "NOOP!";
        }
    };

    private TestExecutor _executor;
    private ReservedThreadExecutor _reservedExecutor;

    @Before
    public void before() throws Exception
    {
        _executor = new TestExecutor();
        _reservedExecutor = new ReservedThreadExecutor(_executor, SIZE);
        _reservedExecutor.start();
    }

    @After
    public void after() throws Exception
    {
        _reservedExecutor.stop();
    }

    @Test
    public void testStarted() throws Exception
    {
        // Reserved threads are lazily started.
        assertThat(_executor._queue.size(), is(0));
    }

    @Test
    public void testPending() throws Exception
    {
        assertThat(_executor._queue.size(), is(0));

        for (int i = 0; i < SIZE; i++)
            _reservedExecutor.tryExecute(NOOP);
        assertThat(_executor._queue.size(), is(SIZE));

        for (int i = 0; i < SIZE; i++)
            _executor.execute();
        assertThat(_executor._queue.size(), is(0));

        waitForAllAvailable();

        for (int i = 0; i < SIZE; i++)
            assertThat(_reservedExecutor.tryExecute(new Task()), is(true));
        assertThat(_executor._queue.size(), is(1));
        assertThat(_reservedExecutor.getAvailable(), is(0));

        for (int i = 0; i < SIZE; i++)
            assertThat(_reservedExecutor.tryExecute(NOOP), is(false));
        assertThat(_executor._queue.size(), is(SIZE));
        assertThat(_reservedExecutor.getAvailable(), is(0));
    }

    @Test
    public void testExecuted() throws Exception
    {
        assertThat(_executor._queue.size(), is(0));

        for (int i = 0; i < SIZE; i++)
            _reservedExecutor.tryExecute(NOOP);
        assertThat(_executor._queue.size(), is(SIZE));

        for (int i = 0; i < SIZE; i++)
            _executor.execute();
        assertThat(_executor._queue.size(), is(0));

        waitForAllAvailable();

        Task[] tasks = new Task[SIZE];
        for (int i = 0; i < SIZE; i++)
        {
            tasks[i] = new Task();
            assertThat(_reservedExecutor.tryExecute(tasks[i]), is(true));
        }

        for (int i = 0; i < SIZE; i++)
            tasks[i]._ran.await(10, TimeUnit.SECONDS);

        assertThat(_executor._queue.size(), is(1));

        Task extra = new Task();
        assertThat(_reservedExecutor.tryExecute(extra), is(false));
        assertThat(_executor._queue.size(), is(2));

        Thread.sleep(500);
        assertThat(extra._ran.getCount(), is(1L));

        for (int i = 0; i < SIZE; i++)
            tasks[i]._complete.countDown();

        waitForAllAvailable();
    }


    @Test
    public void testShrink() throws Exception
    {
        final long IDLE = 1000;

        _reservedExecutor.stop();
        _reservedExecutor.setIdleTimeout(IDLE,TimeUnit.MILLISECONDS);
        _reservedExecutor.start();

        // Reserved threads are lazily started.
        assertThat(_executor._queue.size(), is(0));

        assertThat(_reservedExecutor.tryExecute(NOOP),is(false));
        _executor.execute();
        waitForNoPending();

        CountDownLatch latch = new CountDownLatch(1);
        Runnable waitForLatch = ()->{try {latch.await();} catch(Exception e){}};
        assertThat(_reservedExecutor.tryExecute(waitForLatch),is(true));
        _executor.execute();

        assertThat(_reservedExecutor.tryExecute(NOOP),is(false));
        _executor.execute();
        waitForNoPending();

        latch.countDown();
        waitForAvailable(2);

        // Check that regular moderate activity keeps the pool a moderate size
        TimeUnit.MILLISECONDS.sleep(IDLE/2);
        assertThat(_reservedExecutor.tryExecute(NOOP),is(true));
        waitForAvailable(2);
        TimeUnit.MILLISECONDS.sleep(IDLE/2);
        assertThat(_reservedExecutor.tryExecute(NOOP),is(true));
        waitForAvailable(1);
        TimeUnit.MILLISECONDS.sleep(IDLE/2);
        assertThat(_reservedExecutor.tryExecute(NOOP),is(true));
        waitForAvailable(1);
        TimeUnit.MILLISECONDS.sleep(IDLE/2);
        assertThat(_reservedExecutor.tryExecute(NOOP),is(true));
        waitForAvailable(1);
        TimeUnit.MILLISECONDS.sleep(IDLE/2);
        assertThat(_reservedExecutor.tryExecute(NOOP),is(true));
        waitForAvailable(1);

        // check fully idle goes to zero
        TimeUnit.MILLISECONDS.sleep(IDLE);
        assertThat(_reservedExecutor.getAvailable(),is(0));

    }

    protected void waitForNoPending() throws InterruptedException
    {
        long started = System.nanoTime();
        while (_reservedExecutor.getPending() > 0)
        {
            long elapsed = System.nanoTime() - started;
            if (elapsed > TimeUnit.SECONDS.toNanos(10))
                Assert.fail("pending="+_reservedExecutor.getPending());
            Thread.sleep(10);
        }
        assertThat(_reservedExecutor.getPending(), is(0));
    }

    protected void waitForAvailable(int size) throws InterruptedException
    {
        long started = System.nanoTime();
        while (_reservedExecutor.getAvailable() < size)
        {
            long elapsed = System.nanoTime() - started;
            if (elapsed > TimeUnit.SECONDS.toNanos(10))
                Assert.fail();
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

        public void execute()
        {
            Runnable task = _queue.pollFirst();
            if (task != null)
                new Thread(task).start();
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

    @Ignore
    @Test
    public void stressTest() throws Exception
    {
        QueuedThreadPool pool = new QueuedThreadPool(20);
        pool.setStopTimeout(10000);
        pool.start();
        ReservedThreadExecutor reserved = new ReservedThreadExecutor(pool,10);
        reserved.setIdleTimeout(0,null);
        reserved.start();

        final int LOOPS = 1000000;
        final Random random = new Random();
        final AtomicInteger executions = new AtomicInteger(LOOPS);
        final CountDownLatch executed = new CountDownLatch(executions.get());
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
                            } else
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

        assertTrue(executed.await(60,TimeUnit.SECONDS));

        reserved.stop();
        pool.stop();

        assertThat(usedReserved.get()+usedPool.get(),is(LOOPS));
        System.err.printf("reserved=%d pool=%d total=%d%n",usedReserved.get(),usedPool.get(),LOOPS);
    }
}
