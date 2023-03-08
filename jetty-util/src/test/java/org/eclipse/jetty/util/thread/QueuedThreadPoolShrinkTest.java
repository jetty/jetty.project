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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class QueuedThreadPoolShrinkTest
{

    private static final Logger LOG = LoggerFactory.getLogger(QueuedThreadPoolShrinkTest.class);

    @Test
    public void testSustainedActivity() throws Exception
    {
        // We need a floor on `idleTimeout` because when set too low, we run into issues with threads
        // not being able to accept new jobs immediately after triggering the countdown latch. This is
        // just an artifact of the limitations of testing, and the `idleTimeoutFloor` is absurdly low
        // anyway, wrt what we'd expect it to be set to in practice.
        final int idleTimeoutFloor = 100;
        final int maxThreads = 1000;
        final long seed = new Random().nextLong();
        Random r = new Random(seed);
        final int idleTimeout = r.nextInt(2000 - idleTimeoutFloor) + idleTimeoutFloor;
        final int idleTimeoutDecay = r.nextInt(maxThreads) + 1; // the point of this test is that `idleTimoutDecay` doesn't matter.
        final String configMsg = "idleTimeout=" + idleTimeout + ", idleTimeoutDecay=" + idleTimeoutDecay + ", seed=" + Long.toUnsignedString(seed, 16);
        QueuedThreadPool qtp = new QueuedThreadPool(maxThreads, 0);
        qtp.setIdleTimeout(idleTimeout);
        qtp.setIdleTimeoutDecay(idleTimeoutDecay);
        qtp.start();
        AtomicBoolean abortPing = new AtomicBoolean();
        try
        {
            // set up a background job to keep a target number of threads organically alive
            int targetThreadCount = 500;
            Set<String> threads = new HashSet<>(targetThreadCount);
            Random subR = new Random(r.nextLong());
            qtp.execute(() ->
            {
                try
                {
                    keepPoolAlive(qtp, idleTimeout, targetThreadCount, abortPing, threads, new CountDownLatch(targetThreadCount), 1, subR);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            });

            // Let it run long enough to give threads a chance to idle out (they should
            // _not_ actually idle out -- that's what we're testing).
            Thread.sleep(10000);

            // We expect all requests to be served by the exact same batch of threads (i.e., no
            // threads idled out, which would have necessitated the creation of replacement
            // threads)
            assertEquals(targetThreadCount, threads.size(), configMsg);

            // The overall pool size should account for our requests, plus the background job that
            // issues the requests
            assertEquals(targetThreadCount + 1, qtp.getThreads(), configMsg);
        }
        finally
        {
            abortPing.set(true);
            qtp.stop();
        }
    }

    @Test
    public void testShrinkToSustainedBaseline() throws Exception
    {
        String overrideSeed = null; //"786feaa41a735314";
        final long seed;
        if (overrideSeed == null)
        {
            seed = new Random().nextLong();
        }
        else
        {
            seed = Long.parseUnsignedLong(overrideSeed, 16);
        }
        Random r = new Random(seed);
        final boolean busy = r.nextBoolean();
        final int maxThreads = 1000;
        final int baselineTargetThreadCount = r.nextInt(r.nextBoolean() ? maxThreads : (maxThreads >> 1)) + 1;
        final int expectStableThreadCount = baselineTargetThreadCount + (busy ? 10 : 1);
        final int idleTimeout = 1000;
        final int idleTimeoutDecayFloor = 10;
        final int idleTimeoutDecay;
        if (r.nextBoolean())
        {
            // fast decay
            idleTimeoutDecay = r.nextInt((Math.max(1, (maxThreads - baselineTargetThreadCount) - idleTimeoutDecayFloor))) + idleTimeoutDecayFloor + 1;
        }
        else
        {
            // slow decay
            idleTimeoutDecay = r.nextInt(Math.max(2, (maxThreads - baselineTargetThreadCount) - idleTimeoutDecayFloor) >> 1) + idleTimeoutDecayFloor + 1;
        }
        QueuedThreadPool qtp = new QueuedThreadPool(maxThreads + 1, 0);
        qtp.setIdleTimeout(idleTimeout);
        qtp.setIdleTimeoutDecay(idleTimeoutDecay);
        qtp.start();
        AtomicBoolean abortPing = new AtomicBoolean();
        try
        {
            // spin up the highWaterMark number of threads
            CountDownLatch initCdl = new CountDownLatch(maxThreads);
            for (int i = 0; i < maxThreads; i++)
            {
                int delay = r.nextInt(idleTimeout) + 1;
                qtp.execute(() ->
                {
                    initCdl.countDown();
                    try
                    {
                        initCdl.await();
                        Thread.sleep(delay);
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                });
            }
            initCdl.await();
            assertEquals(maxThreads, qtp.getThreads());

            // set up a background job to keep a baseline target number of threads organically alive
            final int shift = r.nextInt(3) + 1;
            final String configMsg = "baselineTargetThreadCount=" + baselineTargetThreadCount + ", expectStableThreadCount=" + expectStableThreadCount + ", idleTimeoutDecay=" + idleTimeoutDecay + ", busy=" + busy + ", shift=" + shift + ", seed=" + Long.toUnsignedString(seed, 16);
            LOG.info("CONFIG: " + configMsg);

            final CountDownLatch pingCdl = new CountDownLatch(baselineTargetThreadCount);
            Random subR = new Random(r.nextLong());
            qtp.execute(() ->
            {
                try
                {
                    if (busy)
                    {
                        keepPoolBusy(qtp, idleTimeout, baselineTargetThreadCount, abortPing, new HashSet<>(baselineTargetThreadCount), pingCdl, subR);
                    }
                    else
                    {
                        keepPoolAlive(qtp, idleTimeout, baselineTargetThreadCount, abortPing, new HashSet<>(baselineTargetThreadCount), pingCdl, shift, subR);
                    }
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            });
            pingCdl.await();

            // If we don't wait here, there will be threads that have already decided to die, but not yet updated
            // thread pool size count, which will skew results.
            Thread.sleep(idleTimeout << 1);

            final long start = System.nanoTime();
            final int initialThreadCount = qtp.getThreads();
            double expectExpired;
            int threadCount = initialThreadCount;
            final int waitForStableCount = 5;
            int stableCount = 0;
            int samplePeriod = idleTimeout >> 1;
            long precisionCorrect = TimeUnit.MILLISECONDS.toNanos(samplePeriod);
            long tolerance = TimeUnit.MILLISECONDS.toNanos(idleTimeout);
            long expectDurationNanos = (initialThreadCount - baselineTargetThreadCount) * (TimeUnit.MILLISECONDS.toNanos(idleTimeout) / idleTimeoutDecay);
            long requireFinish = start + expectDurationNanos + precisionCorrect + tolerance;
            int consecutiveVarianceFaults = 0;
            while (threadCount > expectStableThreadCount || stableCount < waitForStableCount)
            {
                Thread.sleep(samplePeriod);
                long finishedTime;
                if (threadCount < expectStableThreadCount && !busy)
                {
                    fail("dropped below expected count of " + expectStableThreadCount + "; found " + threadCount);
                }
                else if (threadCount <= expectStableThreadCount)
                {
                    if (stableCount++ == 0)
                    {
                        long now = System.nanoTime();
                        long duration = now - start;
                        // we must correct the expected value for the imprecision of the `idleTimeout` window
                        long actualMillis = TimeUnit.NANOSECONDS.toMillis(duration);
                        if (busy)
                        {
                            long expectMillis = TimeUnit.NANOSECONDS.toMillis(expectDurationNanos + precisionCorrect + tolerance);
                            assertTrue(actualMillis <= expectMillis, configMsg + ", expect=" + expectMillis + ", actual=" + actualMillis);
                        }
                        else
                        {
                            long expectMillis = TimeUnit.NANOSECONDS.toMillis(expectDurationNanos);
                            long delta = TimeUnit.NANOSECONDS.toMillis(precisionCorrect + tolerance);
                            assertEquals(expectMillis, actualMillis, delta, configMsg);
                        }
                    }
                    LOG.info("OK finished threshold=" + expectStableThreadCount + ", actual=" + threadCount);
                    continue;
                }
                else if ((finishedTime = System.nanoTime()) > requireFinish)
                {
                    fail("exceeded expected finish time by " + TimeUnit.NANOSECONDS.toMillis(finishedTime - requireFinish) + " millis");
                }
                threadCount = qtp.getThreads();
                expectExpired = ((double)idleTimeoutDecay / TimeUnit.MILLISECONDS.toNanos(idleTimeout)) * (System.nanoTime() - start);
                long expectThreadCount = Math.round(initialThreadCount - expectExpired);
                double variance = ((double)(initialThreadCount - threadCount) / expectExpired) - 1;
                if (threadCount > expectStableThreadCount && Math.abs(variance) > 0.05)
                {
                    if (consecutiveVarianceFaults++ > 5)
                    {
                        fail("too many consecutive variance faults; expect=" + expectThreadCount + ", actual=" + threadCount + ", variance=" + variance + " (" + configMsg + ")");
                    }
                }
                else
                {
                    consecutiveVarianceFaults = 0;
                }
                LOG.info("OK threadCount expect=" + expectThreadCount + ", actual=" + threadCount + ", variance=" + variance);
            }
        }
        finally
        {
            abortPing.set(true);
            qtp.stop();
        }
    }

    @Test
    public void testPrecise() throws Exception
    {
        final int restoreRetryLimit = ShrinkManager.RETRY_LIMIT;
        ShrinkManager.RETRY_LIMIT = 10;
        try
        {
            testShrinkRatePrecise(5, 5, 5);
            testShrinkRatePrecise(10, 10);
            testShrinkRatePrecise(2, 2, 2, 2, 2, 2);
        }
        finally
        {
            ShrinkManager.RETRY_LIMIT = restoreRetryLimit;
        }
    }

    private void testShrinkRatePrecise(int idleTimeoutDecay, int... expectExpirations) throws Exception
    {
        Map<Integer, Integer> expected = new HashMap<>();
        for (int i = 0; i < expectExpirations.length; i++)
        {
            expected.put(3 + i, expectExpirations[i]);
        }
        QueuedThreadPool qtp = new QueuedThreadPool(1000, 0);
        qtp.setIdleTimeout(1000);
        qtp.setIdleTimeoutDecay(idleTimeoutDecay);
        qtp.start();
        final int count = 10;
        final int taskMillis = 2000;
        try
        {
            CountDownLatch cdl = new CountDownLatch(count);
            for (int i = 0; i < count; i++)
            {
                qtp.execute(() ->
                {
                    try
                    {
                        Thread.sleep(taskMillis);
                        cdl.countDown();
                        cdl.await();
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                });
            }
            long start = System.nanoTime();
            cdl.await();
            assertEquals(count, qtp.getThreads());
            int size = count;
            Map<Integer, Integer> timeoutGroups = new HashMap<>();
            while (size != 0)
            {
                int newSize = qtp.getThreads();
                if (newSize != size)
                {
                    double seconds = (double)(System.nanoTime() - start) / 1000000000;
                    int secondsRounded = (int)Math.round(seconds);
                    int diff = size - newSize;
                    timeoutGroups.compute(secondsRounded, (k, v) -> v == null ? diff : v + diff);
                }
                size = newSize;
            }
            assertEquals(expected, timeoutGroups);
        }
        finally
        {
            qtp.stop();
        }
    }

    @Test
    public void testGeneral() throws Exception
    {
        // NOTE: this test takes a long time, but given that we're evaluating pool shrinkage at varying
        // rates over time, I'm not sure how else to do it.
        testGeneralLoop(1);
        testGeneralLoop(10);
        testGeneralLoop(20);
        testGeneralLoop(30);
        testGeneralLoop(40);
        testGeneralLoop(50);
        testGeneralLoop(60);
        testGeneralLoop(70);
        testGeneralLoop(80);
        testGeneralLoop(90);
        testGeneralLoop(100);
        testGeneralLoop(200);
    }

    private void testGeneralLoop(int idleTimeoutDecay) throws Exception
    {
        for (int idleTimeout = 100; idleTimeout <= 400; idleTimeout += 100)
        {
            double[] actual = new double[1];
            int iterations = testShrinkRateGeneral(idleTimeout, idleTimeoutDecay, actual);
            LOG.info("OK idleTimeoutDecay=" + idleTimeoutDecay + " idleTimeout=" + idleTimeout + ", iterations=" + iterations + ", actual=" + actual[0]);
        }
    }

    private int testShrinkRateGeneral(final int idleTimeout, int idleTimeoutDecay, double[] actual) throws Exception
    {
        final double expected = idleTimeoutDecay;

        final int count = 5000;
        AtomicBoolean abortPing = new AtomicBoolean();
        QueuedThreadPool qtp = new QueuedThreadPool(count << 1, 0);
        qtp.setIdleTimeout(idleTimeout);
        qtp.setIdleTimeoutDecay(idleTimeoutDecay);
        qtp.start();
        final int taskMillis = idleTimeout << 1;
        // The outcome of how this Random is used will not be deterministic anyway, so for our purposes a
        // a static seed would be fine.
        long seed = new Random().nextLong();
        Random r = new Random(seed);
        try
        {
            for (int i = 0; i < count; i++)
            {
                int delay1 = r.nextInt(taskMillis);
                int delay2 = r.nextInt(taskMillis);
                qtp.execute(() ->
                {
                    try
                    {
                        Thread.sleep(delay1);
                        qtp.execute(() ->
                        {
                            try
                            {
                                Thread.sleep(taskMillis + delay2);
                            }
                            catch (InterruptedException e)
                            {
                                throw new RuntimeException(e);
                            }
                        });
                    }
                    catch (InterruptedException e)
                    {
                        throw new RuntimeException(e);
                    }
                });
            }

            // wait until we expect all threads to be idle.
            Thread.sleep((long)taskMillis << 1);

            // very short idleTimeout or minimal idleTimeoutDecay may take a little longer to converge
            int maxIterations = idleTimeout < 200 || idleTimeoutDecay == 1 ? 64 : 32;

            // set up a background job to keep a target number of threads organically alive
            int targetThreadCount = 500;
            if ((qtp.getThreads() - targetThreadCount) / idleTimeoutDecay > maxIterations)
            {
                Set<String> threads = new HashSet<>();
                CountDownLatch latch = new CountDownLatch(targetThreadCount);
                Random subR = new Random(r.nextLong());
                qtp.execute(() ->
                {
                    try
                    {
                        keepPoolAlive(qtp, idleTimeout, targetThreadCount, abortPing, threads, latch, 1, subR);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                });
                latch.await(); // spin up sending threads before `start` baseline
                Thread.sleep(taskMillis);
            }

            long start = System.nanoTime();
            int origSize = qtp.getThreads();
            int size = origSize;
            final int minIterations = 3;
            double ratio = Double.NaN;
            double deviation = Double.NaN;
            final int requireWithinTolerance = 4;
            int countWithinTolerance = 0;

            final double tolerance = 0.05;

            for (int i = 0; i < maxIterations && size > 0; i++)
            {
                Thread.sleep(idleTimeout);
                long now = System.nanoTime();
                int nextSize = qtp.getThreads();
                ratio = ((double)(origSize - nextSize) / (now - start)) * (idleTimeout * 1000000);
                deviation = (ratio / expected) - 1;
                if (i > minIterations && Math.abs(deviation) < tolerance && ++countWithinTolerance >= requireWithinTolerance)
                {
                    actual[0] = ratio;
                    return i;
                }
                size = nextSize;
            }
            fail("expected=" + expected + ", ratio=" + ratio + ", last-deviation " + deviation + ", count=" + countWithinTolerance + ", seed=" + Long.toUnsignedString(seed, 16));
            return maxIterations;
        }
        finally
        {
            abortPing.set(true);
            qtp.stop();
        }
    }

    private static void keepPoolAlive(QueuedThreadPool qtp, long idleTimeout, int targetThreadCount, AtomicBoolean abort, Set<String> threads, CountDownLatch cdl, int shift, Random r) throws InterruptedException
    {
        ExecutorService exec = Executors.newFixedThreadPool(targetThreadCount);
        try
        {
            long pauseMillis = idleTimeout >> shift; // pause some ratio of `idleTimeout` between pinging threads
            do
            {
                Thread.sleep(pauseMillis);
                CountDownLatch latch = cdl;
                for (int i = 0; i < targetThreadCount; i++)
                {
                    long delay = r.nextInt((int)pauseMillis >> 1) + 1;
                    exec.submit(() ->
                    {
                        qtp.execute(() ->
                        {
                            threads.add(Thread.currentThread().getName());
                            latch.countDown();
                            try
                            {
                                latch.await();
                                Thread.sleep(delay);
                            }
                            catch (InterruptedException e)
                            {
                                throw new RuntimeException(e);
                            }
                        });
                    });
                }
                latch.await();
                cdl = new CountDownLatch(targetThreadCount);
            }
            while (!abort.get());
        }
        finally
        {
            exec.shutdown();
        }
    }

    private static void keepPoolBusy(QueuedThreadPool qtp, long idleTimeout, int targetThreadCount, AtomicBoolean abort, Set<String> threads, CountDownLatch cdl, Random r) throws InterruptedException
    {
        ExecutorService exec = Executors.newFixedThreadPool(targetThreadCount);
        int count = (int)cdl.getCount();
        CountDownLatch postCdl = new CountDownLatch(count);
        try
        {
            for (int i = 0; i < targetThreadCount; i++)
            {
                Random subR = new Random(r.nextLong());
                exec.submit(() ->
                {
                    boolean initial = true;
                    while (!abort.get())
                    {
                        CountDownLatch innerLatch = new CountDownLatch(1);
                        qtp.execute(() ->
                        {
                            threads.add(Thread.currentThread().getName());
                            long delay = subR.nextInt((int)idleTimeout);
                            try
                            {
                                Thread.sleep(delay);
                            }
                            catch (InterruptedException e)
                            {
                                throw new RuntimeException(e);
                            }
                            finally
                            {
                                innerLatch.countDown();
                            }
                        });
                        try
                        {
                            innerLatch.await();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                        if (initial)
                        {
                            cdl.countDown();
                            initial = false;
                        }
                    }
                    postCdl.countDown();
                });
            }
            postCdl.await();
        }
        finally
        {
            exec.shutdown();
        }
    }
}
