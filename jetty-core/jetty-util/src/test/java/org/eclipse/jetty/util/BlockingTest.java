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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
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
        long start;
        try (Blocker.Runnable runnable = Blocker.runnable())
        {
            runnable.run();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            runnable.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
    }

    @Test
    public void testBlockRun() throws Exception
    {
        long start;
        try (Blocker.Runnable runnable = Blocker.runnable())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(() ->
            {
                latch.countDown();
                try
                {
                    TimeUnit.MILLISECONDS.sleep(100);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                runnable.run();
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            runnable.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
    }

    @Test
    public void testNoRun() throws Exception
    {
        long start;
        try (Blocker.Runnable ignored = Blocker.runnable())
        {
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            LoggerFactory.getLogger(Blocker.class).info("expect WARN Blocking.Runnable incomplete");
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
    }

    @Test
    public void testSucceededBlock() throws Exception
    {
        long start;
        try (Blocker.Callback callback = Blocker.callback())
        {
            callback.succeeded();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            callback.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
    }

    @Test
    public void testBlockSucceeded() throws Exception
    {
        long start;
        try (Blocker.Callback callback = Blocker.callback())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(() ->
            {
                latch.countDown();
                try
                {
                    TimeUnit.MILLISECONDS.sleep(100);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                callback.succeeded();
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            callback.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
    }

    @Test
    public void testFailedBlock() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
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
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertEquals(ex, e.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(100L));
    }

    @Test
    public void testBlockFailed() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
        final CountDownLatch latch = new CountDownLatch(1);

        try
        {
            try (Blocker.Callback callback = Blocker.callback())
            {

                new Thread(() ->
                {
                    latch.countDown();
                    try
                    {
                        TimeUnit.MILLISECONDS.sleep(100);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                    callback.failed(ex);
                }).start();

                latch.await();
                start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                callback.block();
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            assertEquals(ex, e.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
    }

    @Test
    public void testSharedRunBlock() throws Exception
    {
        long start;
        try (Blocker.Runnable runnable = _shared.runnable())
        {
            runnable.run();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            runnable.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
    }

    @Test
    public void testSharedBlockRun() throws Exception
    {
        long start;
        try (Blocker.Runnable runnable = _shared.runnable())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(() ->
            {
                latch.countDown();
                try
                {
                    TimeUnit.MILLISECONDS.sleep(100);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                runnable.run();
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            runnable.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
    }

    @Test
    public void testSharedNoRun() throws Exception
    {
        long start;
        try (Blocker.Runnable ignored = _shared.runnable())
        {
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            LoggerFactory.getLogger(Blocker.class).info("expect WARN Blocking.Shared incomplete");
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));

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
        long start;
        try (Blocker.Callback callback = _shared.callback())
        {
            callback.succeeded();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            callback.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
    }

    @Test
    public void testSharedBlockSucceeded() throws Exception
    {
        long start;
        try (Blocker.Callback callback = _shared.callback())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(() ->
            {
                latch.countDown();
                try
                {
                    TimeUnit.MILLISECONDS.sleep(100);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
                callback.succeeded();
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            callback.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
    }

    @Test
    public void testSharedFailedBlock() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
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
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertEquals(ex, e.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(100L));
    }

    @Test
    public void testSharedBlockFailed() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
        final CountDownLatch latch = new CountDownLatch(1);

        try
        {
            try (Blocker.Callback callback = _shared.callback())
            {

                new Thread(() ->
                {
                    latch.countDown();
                    try
                    {
                        TimeUnit.MILLISECONDS.sleep(100);
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                    callback.failed(ex);
                }).start();

                latch.await();
                start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                callback.block();
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            assertEquals(ex, e.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
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
                e.printStackTrace();
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
                e.printStackTrace();
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
}
