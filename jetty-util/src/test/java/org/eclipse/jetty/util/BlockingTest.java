//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
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
    private static final Logger LOG = LoggerFactory.getLogger(SharedBlockingCallback.class);

    final AtomicInteger notComplete = new AtomicInteger();
    final Blocking.Shared _shared = new Blocking.Shared();

    @Test
    public void testRunClose() throws Exception
    {
        long start;
        try (Blocking.Runnable runnable = Blocking.runnable())
        {
            runnable.run();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testCloseRun() throws Exception
    {
        long start;
        try (Blocking.Runnable runnable = Blocking.runnable())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(new Runnable()
            {
                @Override
                public void run()
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
                }
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testSucceededClose() throws Exception
    {
        long start;
        try (Blocking.Callback callback = Blocking.callback())
        {
            callback.succeeded();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testCloseSucceeded() throws Exception
    {
        long start;
        try (Blocking.Callback callback = Blocking.callback())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(new Runnable()
            {
                @Override
                public void run()
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
                }
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testFailedClose() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
        try
        {
            try (Blocking.Callback callback = Blocking.callback())
            {
                callback.failed(ex);
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertEquals(ex, e.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(100L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testCloseFailed() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
        final CountDownLatch latch = new CountDownLatch(1);

        try
        {
            try (Blocking.Callback callback = Blocking.callback())
            {

                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).start();

                latch.await();
                start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            assertEquals(ex, e.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testSharedRunClose() throws Exception
    {
        long start;
        try (Blocking.Runnable runnable = _shared.runnable())
        {
            runnable.run();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testSharedCloseRun() throws Exception
    {
        long start;
        try (Blocking.Runnable runnable = _shared.runnable())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(new Runnable()
            {
                @Override
                public void run()
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
                }
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testSharedSucceededClose() throws Exception
    {
        long start;
        try (Blocking.Callback callback = _shared.callback())
        {
            callback.succeeded();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testSharedCloseSucceeded() throws Exception
    {
        long start;
        try (Blocking.Callback callback = _shared.callback())
        {
            final CountDownLatch latch = new CountDownLatch(1);

            new Thread(new Runnable()
            {
                @Override
                public void run()
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
                }
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testSharedFailedClose() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
        try
        {
            try (Blocking.Callback callback = _shared.callback())
            {
                callback.failed(ex);
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertEquals(ex, e.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(100L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testSharedCloseFailed() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
        final CountDownLatch latch = new CountDownLatch(1);

        try
        {
            try (Blocking.Callback callback = _shared.callback())
            {

                new Thread(new Runnable()
                {
                    @Override
                    public void run()
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
                    }
                }).start();

                latch.await();
                start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            assertEquals(ex, e.getCause());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testSharedBlocked() throws Exception
    {
        Blocking.Callback callback0 = _shared.callback();
        CountDownLatch latch0 = new CountDownLatch(2);
        new Thread(() ->
        {
            try (Blocking.Callback callback = _shared.callback())
            {
                latch0.countDown();
                callback.succeeded();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }).start();
        new Thread(() ->
        {
            try (Blocking.Runnable runnable = _shared.runnable())
            {
                latch0.countDown();
                runnable.run();
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }).start();

        assertFalse(latch0.await(100, TimeUnit.MILLISECONDS));
        callback0.succeeded();
        callback0.close();
        assertTrue(latch0.await(10, TimeUnit.SECONDS));
    }
    
    @Test
    public void testInterruptedException() throws Exception
    {
        try
        {
            Blocking.Callback callback = _shared.callback();
            Thread.currentThread().interrupt();
            callback.close();
            fail();
        }
        catch (InterruptedIOException ignored)
        {
        }
    }
}
