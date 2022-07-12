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

import java.io.Closeable;
import java.net.URL;
import java.net.URLClassLoader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ThreadPool.SizedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class QueuedThreadPoolTest extends AbstractThreadPoolTest
{
    private static final Logger LOG = LoggerFactory.getLogger(QueuedThreadPoolTest.class);
    private final AtomicInteger _jobs = new AtomicInteger();

    private static class TestQueuedThreadPool extends QueuedThreadPool
    {
        private final AtomicInteger _started;
        private final CountDownLatch _enteredRemoveThread;
        private final CountDownLatch _exitRemoveThread;

        public TestQueuedThreadPool(AtomicInteger started, CountDownLatch enteredRemoveThread, CountDownLatch exitRemoveThread)
        {
            _started = started;
            _enteredRemoveThread = enteredRemoveThread;
            _exitRemoveThread = exitRemoveThread;
        }

        public void superStartThread()
        {
            super.startThread();
        }

        @Override
        protected void startThread()
        {
            switch (_started.incrementAndGet())
            {
                case 1:
                case 2:
                case 3:
                    super.startThread();
                    break;

                case 4:
                    // deliberately not start thread
                    break;

                default:
                    throw new IllegalStateException("too many threads started");
            }
        }

        @Override
        protected void removeThread(Thread thread)
        {
            try
            {
                _enteredRemoveThread.countDown();
                _exitRemoveThread.await();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }

            super.removeThread(thread);
        }
    }

    private static class StoppingTask implements Runnable
    {
        private final CountDownLatch _running;
        private final CountDownLatch _blocked;
        private final QueuedThreadPool _tp;
        Thread _thread;
        CountDownLatch _completed = new CountDownLatch(1);

        public StoppingTask(CountDownLatch running, CountDownLatch blocked, QueuedThreadPool tp)
        {
            _running = running;
            _blocked = blocked;
            _tp = tp;
        }

        @Override
        public void run()
        {
            try
            {
                _thread = Thread.currentThread();
                _running.countDown();
                _blocked.await();
                _tp.doStop();
                _completed.countDown();
            }
            catch (InterruptedException x)
            {
                x.printStackTrace();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }

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
                LOG.debug("RunningJob failed", e);
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
        public void close()
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
        tp.setIdleTimeout(1000);
        tp.setThreadsPriority(Thread.NORM_PRIORITY - 1);

        tp.start();

        // min threads started
        waitForThreads(tp, 2);
        waitForIdle(tp, 2);

        // Doesn't shrink to less than min threads
        Thread.sleep(3L * tp.getIdleTimeout() / 2);
        assertThat(tp.getThreads(), is(2));
        assertThat(tp.getIdleThreads(), is(2));

        // Run job0
        RunningJob job0 = new RunningJob("JOB0");
        tp.execute(job0);
        assertTrue(job0._run.await(10, TimeUnit.SECONDS));
        assertThat(tp.getThreads(), is(2));
        assertThat(tp.getIdleThreads(), is(1));

        // Run job1
        RunningJob job1 = new RunningJob("JOB1");
        tp.execute(job1);
        assertTrue(job1._run.await(10, TimeUnit.SECONDS));
        assertThat(tp.getThreads(), is(2));
        assertThat(tp.getIdleThreads(), is(0));

        // Run job2
        RunningJob job2 = new RunningJob("JOB2");
        tp.execute(job2);
        assertTrue(job2._run.await(10, TimeUnit.SECONDS));
        assertThat(tp.getThreads(), is(3));
        assertThat(tp.getIdleThreads(), is(0));

        // Run job3
        RunningJob job3 = new RunningJob("JOB3");
        tp.execute(job3);
        assertTrue(job3._run.await(10, TimeUnit.SECONDS));
        assertThat(tp.getThreads(), is(4));
        assertThat(tp.getIdleThreads(), is(0));

        // Check no short term change
        Thread.sleep(100);
        assertThat(tp.getThreads(), is(4));
        assertThat(tp.getIdleThreads(), is(0));

        // Run job4. will be queued
        RunningJob job4 = new RunningJob("JOB4");
        tp.execute(job4);
        assertFalse(job4._run.await(250, TimeUnit.MILLISECONDS));
        assertThat(tp.getThreads(), is(4));
        assertThat(tp.getIdleThreads(), is(0));
        assertThat(tp.getQueueSize(), is(1));

        // finish job 0
        job0._stopping.countDown();
        assertTrue(job0._stopped.await(10, TimeUnit.SECONDS));

        // job4 should now run
        assertTrue(job4._run.await(10, TimeUnit.SECONDS));
        assertThat(tp.getThreads(), is(4));
        assertThat(tp.getIdleThreads(), is(0));
        assertThat(tp.getQueueSize(), is(0));

        // finish job 1, and its thread will become idle
        job1._stopping.countDown();
        assertTrue(job1._stopped.await(10, TimeUnit.SECONDS));
        waitForIdle(tp, 1);
        waitForThreads(tp, 4);

        // finish job 2,3,4
        job2._stopping.countDown();
        job3._stopping.countDown();
        job4._stopping.countDown();
        assertTrue(job2._stopped.await(10, TimeUnit.SECONDS));
        assertTrue(job3._stopped.await(10, TimeUnit.SECONDS));
        assertTrue(job4._stopped.await(10, TimeUnit.SECONDS));

        // At beginning of the test we waited 1.5*idleTimeout, but
        // never actually shrunk the pool because it was at minThreads.
        // Now that all jobs are finished, one thread will figure out
        // that it will go idle and will shrink itself out of the pool.
        // Give it some time to detect that, but not too much to shrink
        // two threads.
        Thread.sleep(tp.getIdleTimeout() / 4);

        // Now we have 3 idle threads.
        waitForIdle(tp, 3);
        assertThat(tp.getThreads(), is(3));

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
            Thread.sleep(3L * tp.getIdleTimeout() / 2);
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
    public void testSteadyShrink() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        Runnable job = () ->
        {
            try
            {
                latch.await();
            }
            catch (InterruptedException e)
            {
                e.printStackTrace();
            }
        };

        QueuedThreadPool tp = new QueuedThreadPool();
        tp.setMinThreads(2);
        tp.setMaxThreads(10);
        int timeout = 500;
        tp.setIdleTimeout(timeout);
        tp.setThreadsPriority(Thread.NORM_PRIORITY - 1);

        tp.start();
        waitForIdle(tp, 2);
        waitForThreads(tp, 2);

        for (int i = 0; i < 10; i++)
        {
            tp.execute(job);
        }

        waitForThreads(tp, 10);
        int threads = tp.getThreads();
        // let the jobs run
        latch.countDown();

        for (int i = 5; i-- > 0; )
        {
            Thread.sleep(timeout / 2);
            tp.execute(job);
        }

        // Assert that steady rate of jobs doesn't prevent some idling out
        assertThat(tp.getThreads(), lessThan(threads));
        threads = tp.getThreads();
        for (int i = 5; i-- > 0; )
        {
            Thread.sleep(timeout / 2);
            tp.execute(job);
        }
        assertThat(tp.getThreads(), lessThan(threads));
    }

    @Test
    public void testEnsureThreads() throws Exception
    {
        AtomicInteger started = new AtomicInteger(0);

        CountDownLatch enteredRemoveThread = new CountDownLatch(1);
        CountDownLatch exitRemoveThread = new CountDownLatch(1);
        TestQueuedThreadPool tp = new TestQueuedThreadPool(started, enteredRemoveThread, exitRemoveThread);

        tp.setMinThreads(2);
        tp.setMaxThreads(10);
        tp.setIdleTimeout(400);
        tp.setThreadsPriority(Thread.NORM_PRIORITY - 1);

        tp.start();
        waitForIdle(tp, 2);
        waitForThreads(tp, 2);

        RunningJob job1 = new RunningJob();
        RunningJob job2 = new RunningJob();
        RunningJob job3 = new RunningJob();
        tp.execute(job1);
        tp.execute(job2);
        tp.execute(job3);

        waitForThreads(tp, 3);
        waitForIdle(tp, 0);

        // We stop job3, the thread becomes idle, thread decides to shrink, and then blocks in removeThread().
        job3.stop();
        assertTrue(enteredRemoveThread.await(5, TimeUnit.SECONDS));
        waitForThreads(tp, 3);
        waitForIdle(tp, 1);

        // Executing job4 will not start a new thread because we already have 1 idle thread.
        RunningJob job4 = new RunningJob();
        tp.execute(job4);

        // Allow thread to exit from removeThread().
        // The 4th thread is not actually started in our startThread() until tp.superStartThread() is called.
        // Delay by 1000ms to check that ensureThreads is only starting one thread even though it is slow to start.
        assertThat(started.get(), is(3));
        exitRemoveThread.countDown();
        Thread.sleep(1000);

        // Now startThreads() should have been called 4 times.
        // Actually start the thread, and job4 should be run.
        assertThat(started.get(), is(4));
        tp.superStartThread();
        assertTrue(job4._run.await(5, TimeUnit.SECONDS));

        job1.stop();
        job2.stop();
        job4.stop();
        tp.stop();
    }

    @Test
    public void testMaxStopTime() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
        long stopTimeout = 500;
        tp.setStopTimeout(stopTimeout);
        tp.start();
        CountDownLatch interruptedLatch = new CountDownLatch(1);
        tp.execute(() ->
        {
            try
            {
                Thread.sleep(10 * stopTimeout);
            }
            catch (InterruptedException expected)
            {
                interruptedLatch.countDown();
            }
        });

        long beforeStop = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        tp.stop();
        long afterStop = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        assertTrue(tp.isStopped());
        assertTrue(afterStop - beforeStop < 1000);
        assertTrue(interruptedLatch.await(5, TimeUnit.SECONDS));
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
        assertThat(tp.getIdleThreads(), is(idle));
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
        assertThat(reservedThreadExecutor.getAvailable(), is(reserved));
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
        assertThat(tp.getThreads(), is(threads));
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
        assertThrows(IllegalArgumentException.class, () -> new QueuedThreadPool(4, 8));
    }

    @Test
    public void testJoinWithStopTimeout() throws Exception
    {
        final long stopTimeout = 100;
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setStopTimeout(100);
        threadPool.start();

        // Verify that join does not timeout after waiting twice the stopTimeout.
        assertThrows(Throwable.class, () ->
            assertTimeoutPreemptively(Duration.ofMillis(stopTimeout * 2), threadPool::join)
        );

        // After stopping the ThreadPool join should unblock.
        LifeCycle.stop(threadPool);
        assertTimeoutPreemptively(Duration.ofMillis(stopTimeout), threadPool::join);
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
        assertThat(count(dump, " IDLE"), is(3));
        assertThat(count(dump, " RESERVED"), is(0));

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
        assertThat(count(dump, " IDLE"), is(2));
        assertThat(count(dump, " WAITING"), is(1));
        assertThat(count(dump, " RESERVED"), is(0));
        assertThat(count(dump, "QueuedThreadPoolTest.lambda$testDump$"), is(0));

        pool.setDetailedDump(true);
        dump = pool.dump();
        assertThat(count(dump, " - STARTED"), is(2));
        assertThat(dump, containsString(",3<=3<=4,i=2,r=2,q=0"));
        assertThat(dump, containsString("reserved=0/2"));
        assertThat(dump, containsString("[ReservedThreadExecutor@"));
        assertThat(count(dump, " IDLE"), is(2));
        assertThat(count(dump, " WAITING"), is(1));
        assertThat(count(dump, " RESERVED"), is(0));
        assertThat(count(dump, "QueuedThreadPoolTest.lambda$testDump$"), is(1));

        assertFalse(pool.tryExecute(() ->
        {
        }));
        waitForReserved(pool, 1);
        Thread.sleep(250); // TODO need to give time for threads to read idle poll after setting idle
        dump = pool.dump();
        assertThat(count(dump, " - STARTED"), is(2));
        assertThat(dump, containsString(",3<=3<=4,i=1,r=2,q=0"));
        assertThat(dump, containsString("reserved=1/2"));
        assertThat(dump, containsString("[ReservedThreadExecutor@"));
        assertThat(count(dump, " IDLE"), is(1));
        assertThat(count(dump, " WAITING"), is(1));
        assertThat(count(dump, " RESERVED"), is(1));
        assertThat(count(dump, "QueuedThreadPoolTest.lambda$testDump$"), is(1));
    }

    @Test
    public void testContextClassLoader() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
        try (StacklessLogging stackless = new StacklessLogging(QueuedThreadPool.class))
        {
            //change the current thread's classloader to something else
            Thread.currentThread().setContextClassLoader(new URLClassLoader(new URL[] {}));
            
            //create a new thread
            Thread t = tp.newThread(() ->
            {
                //the executing thread should be still set to the classloader of the QueuedThreadPool,
                //not that of the thread that created this thread.
                assertThat(Thread.currentThread().getContextClassLoader(), Matchers.equalTo(QueuedThreadPool.class.getClassLoader()));
            });
            
            //new thread should be set to the classloader of the QueuedThreadPool
            assertThat(t.getContextClassLoader(), Matchers.equalTo(QueuedThreadPool.class.getClassLoader()));
        }
    }

    @Test
    public void testThreadCounts() throws Exception
    {
        int maxThreads = 100;
        QueuedThreadPool tp = new QueuedThreadPool(maxThreads, 0);
        // Long timeout so it does not expire threads during the test.
        tp.setIdleTimeout(60000);
        int reservedThreads = 7;
        tp.setReservedThreads(reservedThreads);
        tp.start();
        int leasedThreads = 5;
        tp.getThreadPoolBudget().leaseTo(new Object(), leasedThreads);
        List<RunningJob> leasedJobs = new ArrayList<>();
        for (int i = 0; i < leasedThreads; ++i)
        {
            RunningJob job = new RunningJob("JOB" + i);
            leasedJobs.add(job);
            tp.execute(job);
            assertTrue(job._run.await(5, TimeUnit.SECONDS));
        }

        // Run some job to spawn threads.
        for (int i = 0; i < 3; ++i)
        {
            tp.tryExecute(() -> {});
        }
        int spawned = 13;
        List<RunningJob> jobs = new ArrayList<>();
        for (int i = 0; i < spawned; ++i)
        {
            RunningJob job = new RunningJob("JOB" + i);
            jobs.add(job);
            tp.execute(job);
            assertTrue(job._run.await(5, TimeUnit.SECONDS));
        }
        for (RunningJob job : jobs)
        {
            job._stopping.countDown();
        }

        // Wait for the threads to become idle again.
        Thread.sleep(1000);

        // Submit less jobs to the queue so we have active and idle threads.
        jobs.clear();
        int transientJobs = spawned / 2;
        for (int i = 0; i < transientJobs; ++i)
        {
            RunningJob job = new RunningJob("JOB" + i);
            jobs.add(job);
            tp.execute(job);
            assertTrue(job._run.await(5, TimeUnit.SECONDS));
        }

        try
        {
            assertThat(tp.getMaxReservedThreads(), Matchers.equalTo(reservedThreads));
            assertThat(tp.getLeasedThreads(), Matchers.equalTo(leasedThreads));
            assertThat(tp.getReadyThreads(), Matchers.equalTo(tp.getIdleThreads() + tp.getAvailableReservedThreads()));
            assertThat(tp.getUtilizedThreads(), Matchers.equalTo(transientJobs));
            assertThat(tp.getThreads(), Matchers.equalTo(tp.getReadyThreads() + tp.getLeasedThreads() + tp.getUtilizedThreads()));
            assertThat(tp.getBusyThreads(), Matchers.equalTo(tp.getUtilizedThreads() + tp.getLeasedThreads()));
        }
        finally
        {
            jobs.forEach(job -> job._stopping.countDown());
            leasedJobs.forEach(job -> job._stopping.countDown());
            tp.stop();
        }
    }

    @Test
    public void testInterruptedStop() throws Exception
    {
        QueuedThreadPool tp = new QueuedThreadPool();
        tp.setStopTimeout(1000);
        tp.start();

        CountDownLatch running = new CountDownLatch(3);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch forever = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(1);

        Runnable runForever = () ->
        {
            try
            {
                running.countDown();
                forever.await();
            }
            catch (InterruptedException x)
            {
                interrupted.countDown();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        };

        StoppingTask stopping = new StoppingTask(running, blocked, tp);

        tp.execute(runForever);
        tp.execute(stopping);
        tp.execute(runForever);

        assertTrue(running.await(5, TimeUnit.SECONDS));
        blocked.countDown();
        Thread.sleep(100); // wait until in doStop, then....
        stopping._thread.interrupt(); // spurious interrupt
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        assertTrue(stopping._completed.await(5, TimeUnit.SECONDS));
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
