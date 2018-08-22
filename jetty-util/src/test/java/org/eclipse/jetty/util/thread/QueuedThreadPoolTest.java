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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;
import org.junit.Assert;
import org.junit.Test;

public class QueuedThreadPoolTest extends AbstractThreadPoolTest
{
    private final AtomicInteger _jobs=new AtomicInteger();

    private class RunningJob implements Runnable
    {
        private final CountDownLatch _run = new CountDownLatch(1);
        private final CountDownLatch _stopping = new CountDownLatch(1);
        private final CountDownLatch _stopped = new CountDownLatch(1);
        @Override
        public void run()
        {
            try
            {
                _run.countDown();
                _stopping.await();
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                _jobs.incrementAndGet();
                _stopped.countDown();
            }
        }

        public void stop() throws InterruptedException
        {
            if (_run.await(10,TimeUnit.SECONDS))
                _stopping.countDown();
            if (!_stopped.await(10,TimeUnit.SECONDS))
                throw new IllegalStateException();
        }
    }

    @Test
    public void testThreadPool() throws Exception
    {
        QueuedThreadPool tp= new QueuedThreadPool();
        tp.setMinThreads(2);
        tp.setMaxThreads(4);
        tp.setIdleTimeout(900);
        tp.setThreadsPriority(Thread.NORM_PRIORITY-1);

        tp.start();

        // min threads started
        waitForThreads(tp,2);
        waitForIdle(tp,2);

        // Doesn't shrink less than 1
        Thread.sleep(1100);
        waitForThreads(tp,2);
        waitForIdle(tp,2);

        // Run job0
        RunningJob job0=new RunningJob();
        tp.execute(job0);
        assertTrue(job0._run.await(10,TimeUnit.SECONDS));
        waitForIdle(tp,1);
        
        // Run job1
        RunningJob job1=new RunningJob();
        tp.execute(job1);
        assertTrue(job1._run.await(10,TimeUnit.SECONDS));
        waitForThreads(tp,3);
        waitForIdle(tp,1);
        
        // Run job2
        RunningJob job2=new RunningJob();
        tp.execute(job2);
        assertTrue(job2._run.await(10,TimeUnit.SECONDS));
        waitForThreads(tp,4);
        waitForIdle(tp,1);
        
        // Run job3
        RunningJob job3=new RunningJob();
        tp.execute(job3);
        assertTrue(job3._run.await(10,TimeUnit.SECONDS));
        waitForThreads(tp,4);
        assertThat(tp.getIdleThreads(),is(0));
        Thread.sleep(100);
        assertThat(tp.getIdleThreads(),is(0));

        // Run job4. will be queued
        RunningJob job4=new RunningJob();
        tp.execute(job4);
        assertFalse(job4._run.await(1,TimeUnit.SECONDS));
        
        // finish job 0
        job0._stopping.countDown();
        assertTrue(job0._stopped.await(10,TimeUnit.SECONDS));
        
        // job4 should now run
        assertTrue(job4._run.await(10,TimeUnit.SECONDS));
        waitForThreads(tp,4);
        waitForIdle(tp,0);
        
        // finish job 1,2,3,4
        job1._stopping.countDown();
        job2._stopping.countDown();
        job3._stopping.countDown();
        job4._stopping.countDown();
        assertTrue(job1._stopped.await(10,TimeUnit.SECONDS));
        assertTrue(job2._stopped.await(10,TimeUnit.SECONDS));
        assertTrue(job3._stopped.await(10,TimeUnit.SECONDS));
        assertTrue(job4._stopped.await(10,TimeUnit.SECONDS));
                
        waitForThreads(tp,2);
        waitForIdle(tp,2);
    }

    @Test
    public void testShrink() throws Exception
    {
        final AtomicInteger sleep = new AtomicInteger(100);
        Runnable job = () ->
        {
            try
            {
                Thread.sleep(sleep.get());
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
        };

        QueuedThreadPool tp= new QueuedThreadPool();
        tp.setMinThreads(2);
        tp.setMaxThreads(10);
        tp.setIdleTimeout(400);
        tp.setThreadsPriority(Thread.NORM_PRIORITY-1);

        tp.start();
        waitForIdle(tp,2);
        waitForThreads(tp,2);

        sleep.set(200);
        tp.execute(job);
        tp.execute(job);
        for (int i=0;i<20;i++)
            tp.execute(job);

        waitForThreads(tp,10);
        waitForIdle(tp,0);

        sleep.set(5);
        for (int i=0;i<500;i++)
        {
            tp.execute(job);
            Thread.sleep(10);
        }
        waitForThreads(tp,2);
        waitForIdle(tp,2);
    }

    @Test
    public void testMaxStopTime() throws Exception
    {
        QueuedThreadPool tp= new QueuedThreadPool();
        tp.setStopTimeout(500);
        tp.start();
        tp.execute(() ->
        {
            while (true)
            {
                try
                {
                    Thread.sleep(10000);
                }
                catch (InterruptedException expected)
                {
                }
            }
        });

        long beforeStop = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        tp.stop();
        long afterStop = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        assertTrue(tp.isStopped());
        assertTrue(afterStop - beforeStop < 1000);
    }

    private void waitForIdle(QueuedThreadPool tp, int idle)
    {
        long now=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        long start=now;
        while (tp.getIdleThreads()!=idle && (now-start)<10000)
        {
            try
            {
                Thread.sleep(50);
            }
            catch(InterruptedException ignored)
            {
            }
            now=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        Assert.assertEquals(idle, tp.getIdleThreads());
    }

    private void waitForThreads(QueuedThreadPool tp, int threads)
    {
        long now=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        long start=now;
        while (tp.getThreads()!=threads && (now-start)<10000)
        {
            try
            {
                Thread.sleep(50);
            }
            catch(InterruptedException ignored)
            {
            }
            now=TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertEquals(threads,tp.getThreads());
    }

    @Test
    public void testException() throws Exception
    {
        QueuedThreadPool tp= new QueuedThreadPool();
        tp.setMinThreads(5);
        tp.setMaxThreads(10);
        tp.setIdleTimeout(1000);
        tp.start();
        try (StacklessLogging stackless = new StacklessLogging(QueuedThreadPool.class))
        {
            tp.execute(() -> { throw new IllegalStateException(); });
            tp.execute(() -> { throw new Error(); });
            tp.execute(() -> { throw new RuntimeException(); });
            tp.execute(() -> { throw new ThreadDeath(); });
            
            Thread.sleep(100);
            assertThat(tp.getThreads(),greaterThanOrEqualTo(5));
        }
    }

    @Test
    public void testZeroMinThreads() throws Exception
    {
        int maxThreads = 10;
        int minThreads = 0;
        QueuedThreadPool pool = new QueuedThreadPool(maxThreads, minThreads);
        pool.start();

        final CountDownLatch latch = new CountDownLatch(1);
        pool.execute(latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorMinMaxThreadsValidation()
    {
        new QueuedThreadPool(4, 8);
    }

    @Override
    protected SizedThreadPool newPool(int max)
    {
        return new QueuedThreadPool(max);
    }
    
}
