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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class BlockingTest
{
    final Blocker.Shared _shared = new Blocker.Shared();

    @Test
    public void testRunBlock() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Runnable runnable = Blocker.runnable())
        {
            runnable.run();
            stopWatch.reset();
            runnable.block();
        }
        assertThat(stopWatch.elapsed(), lessThan(500L));
    }

    @Test
    public void testBlockRun() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Runnable runnable = Blocker.runnable())
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            new Thread(() ->
            {
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                    stopWatch.sleep(100);
                    runnable.run();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            barrier.await(5, TimeUnit.SECONDS);
            stopWatch.reset();
            runnable.block();
        }
        long elapsed = stopWatch.elapsed();
        assertThat(elapsed, greaterThan(10L));
        assertThat(elapsed, lessThan(1000L));
    }

    @Test
    public void testNoRun()
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Runnable ignored = Blocker.runnable())
        {
            stopWatch.reset();
            LoggerFactory.getLogger(Blocker.class).info("expect WARN Blocking.Runnable incomplete");
        }
        assertThat(stopWatch.elapsed(), lessThan(500L));
    }

    @Test
    public void testSucceededBlock() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Callback callback = Blocker.callback())
        {
            callback.succeeded();
            stopWatch.reset();
            callback.block();
        }
        assertThat(stopWatch.elapsed(), lessThan(500L));
    }

    @Test
    public void testBlockSucceeded() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Callback callback = Blocker.callback())
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            new Thread(() ->
            {
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                    stopWatch.sleep(100);
                    callback.succeeded();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            barrier.await(5, TimeUnit.SECONDS);
            stopWatch.reset();
            callback.block();
        }
        assertThat(stopWatch.elapsed(), greaterThan(10L));
        assertThat(stopWatch.elapsed(), lessThan(1000L));
    }

    @Test
    public void testFailedBlock()
    {
        Exception ex = new Exception("FAILED");
        StopWatch stopWatch = new StopWatch();
        try
        {
            try (Blocker.Callback callback = Blocker.callback())
            {
                callback.failed(ex);
                callback.block();
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            stopWatch.reset();
            assertEquals(ex, e.getCause());
        }
        assertThat(stopWatch.elapsed(), lessThan(100L));
    }

    @Test
    public void testBlockFailed() throws Exception
    {
        Exception ex = new Exception("FAILED");
        StopWatch stopWatch = new StopWatch();
        CyclicBarrier barrier = new CyclicBarrier(2);
        try
        {
            try (Blocker.Callback callback = Blocker.callback())
            {
                new Thread(() ->
                {
                    try
                    {
                        barrier.await(5, TimeUnit.SECONDS);
                        stopWatch.sleep(100);
                        callback.failed(ex);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }).start();

                barrier.await(5, TimeUnit.SECONDS);
                stopWatch.reset();
                callback.block();
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            assertEquals(ex, e.getCause());
        }
        assertThat(stopWatch.elapsed(), greaterThan(10L));
        assertThat(stopWatch.elapsed(), lessThan(1000L));
    }

    @Test
    public void testSharedRunBlock() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Runnable runnable = _shared.runnable())
        {
            runnable.run();
            stopWatch.reset();
            runnable.block();
        }
        assertThat(stopWatch.elapsed(), lessThan(500L));
    }

    @Test
    public void testSharedBlockRun() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Runnable runnable = _shared.runnable())
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            new Thread(() ->
            {
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                    stopWatch.sleep(100);
                    runnable.run();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            barrier.await(5, TimeUnit.SECONDS);
            stopWatch.reset();
            runnable.block();
        }
        assertThat(stopWatch.elapsed(), greaterThan(10L));
        assertThat(stopWatch.elapsed(), lessThan(1000L));
    }

    @Test
    public void testSharedNoRun() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Runnable ignored = _shared.runnable())
        {
            stopWatch.reset();
            LoggerFactory.getLogger(Blocker.class).info("expect WARN Blocking.Shared incomplete");
        }
        assertThat(stopWatch.elapsed(), lessThan(500L));

        // check it is still operating.
        try (Blocker.Runnable runnable = _shared.runnable())
        {
            runnable.run();
            runnable.block();
        }
    }

    @Test
    public void testSharedSucceededBlock() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Callback callback = _shared.callback())
        {
            callback.succeeded();
            stopWatch.reset();
            callback.block();
        }
        assertThat(stopWatch.elapsed(), lessThan(500L));
    }

    @Test
    public void testSharedBlockSucceeded() throws Exception
    {
        StopWatch stopWatch = new StopWatch();
        try (Blocker.Callback callback = _shared.callback())
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            new Thread(() ->
            {
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                    stopWatch.sleep(100);
                    callback.succeeded();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            barrier.await(5, TimeUnit.SECONDS);
            stopWatch.reset();
            callback.block();
        }
        assertThat(stopWatch.elapsed(), greaterThan(10L));
        assertThat(stopWatch.elapsed(), lessThan(1000L));
    }

    @Test
    public void testSharedFailedBlock()
    {
        Exception ex = new Exception("FAILED");
        StopWatch stopWatch = new StopWatch();
        try
        {
            try (Blocker.Callback callback = _shared.callback())
            {
                callback.failed(ex);
                callback.block();
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            stopWatch.reset();
            assertEquals(ex, e.getCause());
        }
        assertThat(stopWatch.elapsed(), lessThan(100L));
    }

    @Test
    public void testSharedBlockFailed() throws Exception
    {
        Exception ex = new Exception("FAILED");
        StopWatch stopWatch = new StopWatch();
        CyclicBarrier barrier = new CyclicBarrier(2);

        try
        {
            try (Blocker.Callback callback = _shared.callback())
            {
                new Thread(() ->
                {
                    try
                    {
                        barrier.await(5, TimeUnit.SECONDS);
                        stopWatch.sleep(100);
                        callback.failed(ex);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }).start();

                barrier.await(5, TimeUnit.SECONDS);
                stopWatch.reset();
                callback.block();
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            assertEquals(ex, e.getCause());
        }
        assertThat(stopWatch.elapsed(), greaterThan(10L));
        assertThat(stopWatch.elapsed(), lessThan(1000L));
    }

    @Test
    public void testSharedBlocked() throws Exception
    {
        Blocker.Callback callback0 = _shared.callback();
        CountDownLatch latch0 = new CountDownLatch(2);
        new Thread(() ->
        {
            try (Blocker.Callback callback = _shared.callback())
            {
                latch0.countDown();
                callback.succeeded();
                callback.block();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }).start();
        new Thread(() ->
        {
            try (Blocker.Runnable runnable = _shared.runnable())
            {
                latch0.countDown();
                runnable.run();
                runnable.block();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }).start();

        assertFalse(latch0.await(100, TimeUnit.MILLISECONDS));
        callback0.succeeded();
        callback0.block();
        callback0.close();
        assertTrue(latch0.await(10, TimeUnit.SECONDS));
    }
    
    @Test
    public void testInterruptedException() throws Exception
    {
        try
        {
            Blocker.Callback callback = _shared.callback();
            Thread.currentThread().interrupt();
            callback.block();
            fail();
        }
        catch (InterruptedIOException ignored)
        {
        }
    }

    private static class StopWatch
    {
        private volatile long timestamp = NanoTime.now();

        void reset()
        {
            timestamp = NanoTime.now();
        }

        long elapsed()
        {
            return NanoTime.millisSince(timestamp);
        }

        void sleep(long delayInMs)
        {
            if (delayInMs > 4000)
                throw new IllegalArgumentException("Delay is too long for a test: " + delayInMs);
            await().atMost(5, TimeUnit.SECONDS).until(() -> NanoTime.millisSince(timestamp), greaterThanOrEqualTo(delayInMs));
        }
    }
}
