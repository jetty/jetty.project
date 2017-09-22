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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class ReservedThreadExecutorTest
{
    private static final int SIZE = 2;
    private static final Runnable NOOP = () -> {};

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

    protected void waitForAllAvailable() throws InterruptedException
    {
        long started = System.nanoTime();
        while (_reservedExecutor.getAvailable() < SIZE)
        {
            long elapsed = System.nanoTime() - started;
            if (elapsed > TimeUnit.SECONDS.toNanos(10))
                Assert.fail();
            Thread.sleep(10);
        }
        assertThat(_reservedExecutor.getAvailable(), is(SIZE));
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
}
