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
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.SharedBlockingCallback.Blocker;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@Deprecated
public class SharedBlockingCallbackTest
{
    private static final Logger LOG = LoggerFactory.getLogger(SharedBlockingCallback.class);

    final AtomicInteger notComplete = new AtomicInteger();
    final SharedBlockingCallback sbcb = new SharedBlockingCallback()
    {
        @Override
        protected void notComplete(Blocker blocker)
        {
            super.notComplete(blocker);
            notComplete.incrementAndGet();
        }
    };

    @Test
    public void testDone() throws Exception
    {
        long start;
        try (Blocker blocker = sbcb.acquire())
        {
            blocker.succeeded();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            blocker.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testGetDone() throws Exception
    {
        long start;
        try (final Blocker blocker = sbcb.acquire())
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
                    blocker.succeeded();
                }
            }).start();

            latch.await();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            blocker.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(1000L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testFailed() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
        try
        {
            try (final Blocker blocker = sbcb.acquire())
            {
                blocker.failed(ex);
                blocker.block();
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
    public void testGetFailed() throws Exception
    {
        final Exception ex = new Exception("FAILED");
        long start = Long.MIN_VALUE;
        final CountDownLatch latch = new CountDownLatch(1);

        try
        {
            try (final Blocker blocker = sbcb.acquire())
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
                        blocker.failed(ex);
                    }
                }).start();

                latch.await();
                start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                blocker.block();
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
    public void testAcquireBlocked() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);

        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    try (Blocker blocker = sbcb.acquire())
                    {
                        latch.countDown();
                        TimeUnit.MILLISECONDS.sleep(100);
                        blocker.succeeded();
                        blocker.block();
                    }
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
        }).start();

        latch.await();
        long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        try (Blocker blocker = sbcb.acquire())
        {
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, greaterThan(10L));
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));

            blocker.succeeded();
            blocker.block();
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(600L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testBlockerClose() throws Exception
    {
        try (Blocker blocker = sbcb.acquire())
        {
            LOG.info("Blocker not complete " + blocker + " warning is expected...");
        }

        assertEquals(1, notComplete.get());
    }

    @Test
    public void testInterruptedException() throws Exception
    {
        Blocker blocker0;
        try (Blocker blocker = sbcb.acquire())
        {
            blocker0 = blocker;
            Thread.currentThread().interrupt();
            try
            {
                blocker.block();
                fail();
            }
            catch (InterruptedIOException ignored)
            {
            }
        }
        // Blocker.close() has been called by try-with-resources.
        // Simulate callback completion, must not throw.
        LOG.info("Succeeded after ... warning is expected...");
        blocker0.succeeded();

        try (Blocker blocker = sbcb.acquire())
        {
            assertThat(blocker, not(sameInstance(blocker0)));
            blocker.succeeded();
        }
    }
}
