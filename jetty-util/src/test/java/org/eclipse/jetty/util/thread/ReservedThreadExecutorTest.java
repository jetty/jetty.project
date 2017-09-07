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
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class ReservedThreadExecutorTest
{
    final static int SIZE = 2;
    TestExecutor _executor;
    ReservedThreadExecutor _pae;
        
    @Before
    public void before() throws Exception
    {
        _executor = new TestExecutor();
        _pae = new ReservedThreadExecutor(_executor,SIZE);
        _pae.start();
    }
    
    @After
    public void after() throws Exception
    {
        _pae.stop();
    }

    @Test
    public void testStarted() throws Exception
    {
        assertThat(_executor._queue.size(),is(SIZE));
        while(!_executor._queue.isEmpty())
            _executor.execute();
        
        assertThat(_pae.getCapacity(),is(SIZE));
        
        long started = System.nanoTime();
        while (_pae.getAvailable()<SIZE)
        {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)>10)
                break;
            Thread.sleep(100);
        }
        assertThat(_pae.getAvailable(),is(SIZE));
    }

    @Test
    public void testPending() throws Exception
    {
        assertThat(_executor._queue.size(),is(SIZE));
        assertThat(_pae.tryExecute(new NOOP()),is(false));
        assertThat(_executor._queue.size(),is(SIZE));
        
        _executor.execute();
        assertThat(_executor._queue.size(),is(SIZE-1));
        while (!_executor._queue.isEmpty())
            _executor.execute();

        long started = System.nanoTime();
        while (_pae.getAvailable()<SIZE)
        {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)>10)
                break;
            Thread.sleep(100);
        }
        assertThat(_executor._queue.size(),is(0));
        assertThat(_pae.getAvailable(),is(SIZE));
        
        for (int i=SIZE;i-->0;)
            assertThat(_pae.tryExecute(new Task()),is(true));
        assertThat(_executor._queue.size(),is(1));
        assertThat(_pae.getAvailable(),is(0));

        for (int i=SIZE;i-->0;)
            assertThat(_pae.tryExecute(new NOOP()),is(false));
        assertThat(_executor._queue.size(),is(SIZE));
        assertThat(_pae.getAvailable(),is(0));
        
        assertThat(_pae.tryExecute(new NOOP()),is(false));
        assertThat(_executor._queue.size(),is(SIZE));
        assertThat(_pae.getAvailable(),is(0));
    }

    @Test
    public void testExecuted() throws Exception
    {
        while(!_executor._queue.isEmpty())
            _executor.execute();
        long started = System.nanoTime();
        while (_pae.getAvailable()<SIZE)
        {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)>10)
                break;
            Thread.sleep(100);
        }
        assertThat(_pae.getAvailable(),is(SIZE));
        
        Task[] task = new Task[SIZE];
        for (int i=SIZE;i-->0;)
        {
            task[i] = new Task();
            assertThat(_pae.tryExecute(task[i]),is(true));
        }

        for (int i=SIZE;i-->0;)
        {
            task[i]._ran.await(10,TimeUnit.SECONDS);
        }

        assertThat(_executor._queue.size(),is(1));
        Task extra = new Task();
        assertThat(_pae.tryExecute(extra),is(false));
        assertThat(_executor._queue.size(),is(2));
        Thread.sleep(100);
        assertThat(extra._ran.getCount(),is(1L));

        for (int i=SIZE;i-->0;)
        {
            task[i]._complete.countDown();
        }
        
        started = System.nanoTime();
        while (_pae.getAvailable()<SIZE)
        {
            if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-started)>10)
                break;
            Thread.sleep(100);
        }
        assertThat(_pae.getAvailable(),is(SIZE));
    }

    private static class TestExecutor implements Executor
    {
        Deque<Runnable> _queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable task)
        {
            _queue.addLast(task);
        }
        
        public void execute()
        {
            Runnable task = _queue.pollFirst();
            if (task!=null)
                new Thread(task).start();
        }
    }
    
    private static class NOOP implements Runnable
    {
        @Override
        public void run() {}
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
