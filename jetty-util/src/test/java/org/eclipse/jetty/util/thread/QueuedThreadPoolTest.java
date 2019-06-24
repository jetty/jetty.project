//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueuedThreadPoolTest extends AbstractThreadPoolTest
{
    private static final Logger LOG = Log.getLogger(QueuedThreadPoolTest.class);
    private final AtomicInteger _jobs = new AtomicInteger();

    private class RunningJob implements Runnable
    {
        final CountDownLatch _run = new CountDownLatch(1);
        final CountDownLatch _stopping = new CountDownLatch(1);
        final CountDownLatch _stopped = new CountDownLatch(1);
        final String _name;
        final boolean _fail;

        RunningJob()
        {
            this(null, false);
        }

        public RunningJob(String name)
        {
            this(name, false);
        }

        public RunningJob(boolean fail)
        {
            this(null, fail);
        }

        public RunningJob(String name, boolean fail)
        {
            _name = name;
            _fail = fail;
        }

        @Override
        public void run()
        {
            try
            {
                _run.countDown();
                _stopping.await();
                if (_fail)
                    throw new IllegalStateException("Testing!");
            }
            catch (IllegalStateException e)
            {
                throw e;
            }
            catch (Exception e)
            {
                LOG.debug(e);
            }
            finally
            {
                _jobs.incrementAndGet();
                _stopped.countDown();
            }
        }

        public void stop() throws InterruptedException
        {
            if (_run.await(10, TimeUnit.SECONDS))
                _stopping.countDown();
            if (!_stopped.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException();
        }

        @Override
        public String toString()
        {
            if (_name == null)
                return super.toString();
            return String.format("%s@%x", _name, hashCode());
        }
    }

    private class CloseableJob extends RunningJob implements Closeable
    {
        final CountDownLatch _closed = new CountDownLatch(1);

        @Override
        public void close() throws IOException
        {
            _closed.countDown();
        }
    }

    @Test
    public void testThreadPool() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
        tp.setMinThreads(2);
        tp.setMaxThreads(4);
        tp.setIdleTimeout(900);
        tp.setThreadsPriority(Thread.NORM_PRIORITY - 1);

        tp.start();

        // min threads started
        waitForThreads(tp, 2);
        waitForIdle(tp, 2);

        // Doesn't shrink to less than min threads
        Thread.sleep(3 * tp.getIdleTimeout() / 2);
        waitForThreads(tp, 2);
        waitForIdle(tp, 2);

        // Run job0
        RunningJob job0 = new RunningJob("JOB0");
        tp.execute(job0);
        assertTrue(job0._run.await(10, TimeUnit.SECONDS));
        waitForIdle(tp, 1);

        // Run job1
        RunningJob job1 = new RunningJob("JOB1");
        tp.execute(job1);
        assertTrue(job1._run.await(10, TimeUnit.SECONDS));
        waitForThreads(tp, 2);
        waitForIdle(tp, 0);

        // Run job2
        RunningJob job2 = new RunningJob("JOB2");
        tp.execute(job2);
        assertTrue(job2._run.await(10, TimeUnit.SECONDS));
        waitForThreads(tp, 3);
        waitForIdle(tp, 0);

        // Run job3
        RunningJob job3 = new RunningJob("JOB3");
        tp.execute(job3);
        assertTrue(job3._run.await(10, TimeUnit.SECONDS));
        waitForThreads(tp, 4);
        waitForIdle(tp, 0);
        assertThat(tp.getIdleThreads(), is(0));
        Thread.sleep(100);
        assertThat(tp.getIdleThreads(), is(0));

        // Run job4. will be queued
        RunningJob job4 = new RunningJob("JOB4");
        tp.execute(job4);
        assertFalse(job4._run.await(1, TimeUnit.SECONDS));
        assertThat(tp.getThreads(), is(4));

        // finish job 0
        job0._stopping.countDown();
        assertTrue(job0._stopped.await(10, TimeUnit.SECONDS));

        // job4 should now run
        assertTrue(job4._run.await(10, TimeUnit.SECONDS));
        assertThat(tp.getThreads(), is(4));
        assertThat(tp.getIdleThreads(), is(0));

        // finish job 1
        job1._stopping.countDown();
        assertTrue(job1._stopped.await(10, TimeUnit.SECONDS));
        waitForIdle(tp, 1);
        assertThat(tp.getThreads(), is(4));

        // finish job 2,3,4
        job2._stopping.countDown();
        job3._stopping.countDown();
        job4._stopping.countDown();
        assertTrue(job2._stopped.await(10, TimeUnit.SECONDS));
        assertTrue(job3._stopped.await(10, TimeUnit.SECONDS));
        assertTrue(job4._stopped.await(10, TimeUnit.SECONDS));

        waitForIdle(tp, 4);
        assertThat(tp.getThreads(), is(4));

        long duration = System.nanoTime();
        waitForThreads(tp, 3);
        assertThat(tp.getIdleThreads(), is(3));
        duration = System.nanoTime() - duration;
        assertThat(TimeUnit.NANOSECONDS.toMillis(duration), Matchers.greaterThan(tp.getIdleTimeout() / 2L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(duration), Matchers.lessThan(tp.getIdleTimeout() * 2L));

        tp.stop();
    }

    @Test
    public void testThreadPoolFailingJobs() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
        tp.setMinThreads(2);
        tp.setMaxThreads(4);
        tp.setIdleTimeout(900);
        tp.setThreadsPriority(Thread.NORM_PRIORITY - 1);

        try (StacklessLogging stackless = new StacklessLogging(QueuedThreadPool.class))
        {
            tp.start();

            // min threads started
            waitForThreads(tp, 2);
            waitForIdle(tp, 2);

            // Doesn't shrink to less than min threads
            Thread.sleep(3 * tp.getIdleTimeout() / 2);
            waitForThreads(tp, 2);
            waitForIdle(tp, 2);

            // Run job0
            RunningJob job0 = new RunningJob("JOB0", true);
            tp.execute(job0);
            assertTrue(job0._run.await(10, TimeUnit.SECONDS));
            waitForIdle(tp, 1);

            // Run job1
            RunningJob job1 = new RunningJob("JOB1", true);
            tp.execute(job1);
            assertTrue(job1._run.await(10, TimeUnit.SECONDS));
            waitForThreads(tp, 2);
            waitForIdle(tp, 0);

            // Run job2
            RunningJob job2 = new RunningJob("JOB2", true);
            tp.execute(job2);
            assertTrue(job2._run.await(10, TimeUnit.SECONDS));
            waitForThreads(tp, 3);
            waitForIdle(tp, 0);

            // Run job3
            RunningJob job3 = new RunningJob("JOB3", true);
            tp.execute(job3);
            assertTrue(job3._run.await(10, TimeUnit.SECONDS));
            waitForThreads(tp, 4);
            waitForIdle(tp, 0);
            assertThat(tp.getIdleThreads(), is(0));
            Thread.sleep(100);
            assertThat(tp.getIdleThreads(), is(0));

            // Run job4. will be queued
            RunningJob job4 = new RunningJob("JOB4", true);
            tp.execute(job4);
            assertFalse(job4._run.await(1, TimeUnit.SECONDS));

            // finish job 0
            job0._stopping.countDown();
            assertTrue(job0._stopped.await(10, TimeUnit.SECONDS));

            // job4 should now run
            assertTrue(job4._run.await(10, TimeUnit.SECONDS));
            assertThat(tp.getThreads(), is(4));
            assertThat(tp.getIdleThreads(), is(0));

            // finish job 1
            job1._stopping.countDown();
            assertTrue(job1._stopped.await(10, TimeUnit.SECONDS));
            waitForThreads(tp, 3);
            assertThat(tp.getIdleThreads(), is(0));

            // finish job 2,3,4
            job2._stopping.countDown();
            job3._stopping.countDown();
            job4._stopping.countDown();
            assertTrue(job2._stopped.await(10, TimeUnit.SECONDS));
            assertTrue(job3._stopped.await(10, TimeUnit.SECONDS));
            assertTrue(job4._stopped.await(10, TimeUnit.SECONDS));

            waitForIdle(tp, 2);
            waitForThreads(tp, 2);
        }

        tp.stop();
    }

    @Test
    public void testExecuteNoIdleThreads() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
        tp.setDetailedDump(true);
        tp.setMinThreads(1);
        tp.setMaxThreads(10);
        tp.setIdleTimeout(500);

        tp.start();

        RunningJob job1 = new RunningJob();
        tp.execute(job1);

        RunningJob job2 = new RunningJob();
        tp.execute(job2);

        RunningJob job3 = new RunningJob();
        tp.execute(job3);

        // make sure these jobs have started running
        assertTrue(job1._run.await(5, TimeUnit.SECONDS));
        assertTrue(job2._run.await(5, TimeUnit.SECONDS));
        assertTrue(job3._run.await(5, TimeUnit.SECONDS));

        waitForThreads(tp, 3);
        assertThat(tp.getIdleThreads(), is(0));

        job1._stopping.countDown();
        assertTrue(job1._stopped.await(10, TimeUnit.SECONDS));
        waitForIdle(tp, 1);
        assertThat(tp.getThreads(), is(3));

        waitForIdle(tp, 0);
        assertThat(tp.getThreads(), is(2));

        RunningJob job4 = new RunningJob();
        tp.execute(job4);
        assertTrue(job4._run.await(5, TimeUnit.SECONDS));

        tp.stop();
    }

    @Test
    public void testLifeCycleStop() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
        tp.setName("TestPool");
        tp.setMinThreads(1);
        tp.setMaxThreads(2);
        tp.setIdleTimeout(900);
        tp.setStopTimeout(500);
        tp.setThreadsPriority(Thread.NORM_PRIORITY - 1);
        tp.start();

        // min threads started
        waitForThreads(tp, 1);
        waitForIdle(tp, 1);

        // Run job0 and job1
        RunningJob job0 = new RunningJob();
        RunningJob job1 = new RunningJob();
        tp.execute(job0);
        tp.execute(job1);

        // Add more jobs (which should not be run)
        RunningJob job2 = new RunningJob();
        CloseableJob job3 = new CloseableJob();
        RunningJob job4 = new RunningJob();
        tp.execute(job2);
        tp.execute(job3);
        tp.execute(job4);

        // Wait until the first 2 start running
        waitForThreads(tp, 2);
        waitForIdle(tp, 0);
        assertTrue(job0._run.await(200, TimeUnit.MILLISECONDS));
        assertTrue(job1._run.await(200, TimeUnit.MILLISECONDS));

        // Queue should be empty after thread pool is stopped
        tp.stop();
        assertThat(tp.getQueue().size(), is(0));

        // First 2 jobs closed by InterruptedException
        assertTrue(job0._stopped.await(200, TimeUnit.MILLISECONDS));
        assertTrue(job1._stopped.await(200, TimeUnit.MILLISECONDS));

        // Verify RunningJobs in the queue have not been run
        assertFalse(job2._run.await(200, TimeUnit.MILLISECONDS));
        assertFalse(job4._run.await(200, TimeUnit.MILLISECONDS));

        // Verify ClosableJobs have not been run but have been closed
        assertFalse(job3._run.await(200, TimeUnit.MILLISECONDS));
        assertTrue(job3._closed.await(200, TimeUnit.MILLISECONDS));
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
            catch (Exception e)
            {
                e.printStackTrace();
            }
        };

        QueuedThreadPool tp = new QueuedThreadPool();
        tp.setMinThreads(2);
        tp.setMaxThreads(10);
        tp.setIdleTimeout(400);
        tp.setThreadsPriority(Thread.NORM_PRIORITY - 1);

        tp.start();
        waitForIdle(tp, 2);
        waitForThreads(tp, 2);

        sleep.set(200);
        tp.execute(job);
        tp.execute(job);
        for (int i = 0; i < 20; i++)
        {
            tp.execute(job);
        }

        waitForThreads(tp, 10);
        waitForIdle(tp, 0);

        sleep.set(5);
        for (int i = 0; i < 500; i++)
        {
            tp.execute(job);
            Thread.sleep(10);
        }
        waitForThreads(tp, 2);
        waitForIdle(tp, 2);
        tp.stop();
    }

    @Test
    public void testMaxStopTime() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
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
        long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        long start = now;
        while (tp.getIdleThreads() != idle && (now - start) < 10000)
        {
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException ignored)
            {
            }
            now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertEquals(idle, tp.getIdleThreads());
    }

    private void waitForReserved(QueuedThreadPool tp, int reserved)
    {
        long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        long start = now;
        ReservedThreadExecutor reservedThreadExecutor = tp.getBean(ReservedThreadExecutor.class);
        while (reservedThreadExecutor.getAvailable() != reserved && (now - start) < 10000)
        {
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException ignored)
            {
            }
            now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertEquals(reserved, reservedThreadExecutor.getAvailable());
    }

    private void waitForThreads(QueuedThreadPool tp, int threads)
    {
        long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        long start = now;
        while (tp.getThreads() != threads && (now - start) < 10000)
        {
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException ignored)
            {
            }
            now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertEquals(threads, tp.getThreads());
    }

    @Test
    public void testException() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
        tp.setMinThreads(5);
        tp.setMaxThreads(10);
        tp.setIdleTimeout(1000);
        tp.start();
        try (StacklessLogging stackless = new StacklessLogging(QueuedThreadPool.class))
        {
            tp.execute(() ->
            {
                throw new IllegalStateException();
            });
            tp.execute(() ->
            {
                throw new Error();
            });
            tp.execute(() ->
            {
                throw new RuntimeException();
            });
            tp.execute(() ->
            {
                throw new ThreadDeath();
            });

            Thread.sleep(100);
            assertThat(tp.getThreads(), greaterThanOrEqualTo(5));
        }
        tp.stop();
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

    @Test
    public void testConstructorMinMaxThreadsValidation()
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            new QueuedThreadPool(4, 8);
        });
    }

    @Test
    public void testDump() throws Exception
    {
        QueuedThreadPool pool = new QueuedThreadPool(4, 3);
        pool.setIdleTimeout(10000);

        String dump = pool.dump();
        // TODO use hamcrest 2.0 regex matcher
        assertThat(dump, containsString("STOPPED"));
        assertThat(dump, containsString(",3<=0<=4,i=0,r=-1,q=0"));
        assertThat(dump, containsString("[NO_TRY]"));

        pool.setReservedThreads(2);
        dump = pool.dump();
        assertThat(dump, containsString("STOPPED"));
        assertThat(dump, containsString(",3<=0<=4,i=0,r=2,q=0"));
        assertThat(dump, containsString("[NO_TRY]"));

        pool.start();
        waitForIdle(pool, 3);
        Thread.sleep(250); // TODO need to give time for threads to read idle poll after setting idle
        dump = pool.dump();
        assertThat(count(dump, " - STARTED"), is(2));
        assertThat(dump, containsString(",3<=3<=4,i=3,r=2,q=0"));
        assertThat(dump, containsString("[ReservedThreadExecutor@"));
        assertThat(count(dump, " IDLE "), is(3));
        assertThat(count(dump, " RESERVED "), is(0));

        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(1);
        pool.execute(() ->
        {
            try
            {
                started.countDown();
                waiting.await();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        });
        started.await();
        Thread.sleep(250); // TODO need to give time for threads to read idle poll after setting idle
        dump = pool.dump();
        assertThat(count(dump, " - STARTED"), is(2));
        assertThat(dump, containsString(",3<=3<=4,i=2,r=2,q=0"));
        assertThat(dump, containsString("[ReservedThreadExecutor@"));
        assertThat(count(dump, " IDLE "), is(2));
        assertThat(count(dump, " WAITING "), is(1));
        assertThat(count(dump, " RESERVED "), is(0));
        assertThat(count(dump, "QueuedThreadPoolTest.lambda$testDump$"), is(0));

        pool.setDetailedDump(true);
        dump = pool.dump();
        assertThat(count(dump, " - STARTED"), is(2));
        assertThat(dump, containsString(",3<=3<=4,i=2,r=2,q=0"));
        assertThat(dump, containsString("s=0/2"));
        assertThat(dump, containsString("[ReservedThreadExecutor@"));
        assertThat(count(dump, " IDLE "), is(2));
        assertThat(count(dump, " WAITING "), is(1));
        assertThat(count(dump, " RESERVED "), is(0));
        assertThat(count(dump, "QueuedThreadPoolTest.lambda$testDump$"), is(1));

        assertFalse(pool.tryExecute(() ->
        {
        }));
        waitForReserved(pool, 1);
        Thread.sleep(250); // TODO need to give time for threads to read idle poll after setting idle
        dump = pool.dump();
        assertThat(count(dump, " - STARTED"), is(2));
        assertThat(dump, containsString(",3<=3<=4,i=1,r=2,q=0"));
        assertThat(dump, containsString("s=1/2"));
        assertThat(dump, containsString("[ReservedThreadExecutor@"));
        assertThat(count(dump, " IDLE "), is(1));
        assertThat(count(dump, " WAITING "), is(1));
        assertThat(count(dump, " RESERVED "), is(1));
        assertThat(count(dump, "QueuedThreadPoolTest.lambda$testDump$"), is(1));
    }

    private int count(String s, String p)
    {
        int c = 0;
        int i = s.indexOf(p);
        while (i >= 0)
        {
            c++;
            i = s.indexOf(p, i + 1);
        }
        return c;
    }

    @Override
    protected SizedThreadPool newPool(int max)
    {
        return new QueuedThreadPool(max);
    }
}
