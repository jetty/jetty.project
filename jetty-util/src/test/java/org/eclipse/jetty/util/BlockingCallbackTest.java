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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class BlockingCallbackTest
{
    final AtomicInteger notComplete = new AtomicInteger();

    @Test
    public void testDone() throws Exception
    {
        long start;
        try (BlockingCallback blocker = new BlockingCallback())
        {
            blocker.succeeded();
            start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start, lessThan(500L));
        assertEquals(0, notComplete.get());
    }

    @Test
    public void testGetDone() throws Exception
    {
        long start;
        try (BlockingCallback blocker = new BlockingCallback())
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
            try (BlockingCallback blocker = new BlockingCallback())
            {
                blocker.failed(ex);
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
            try (BlockingCallback blocker = new BlockingCallback())
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
    public void testInterruptedException() throws Exception
    {
        BlockingCallback blocker = new BlockingCallback();
        Thread.currentThread().interrupt();
        try
        {
            blocker.close();
            fail();
        }
        catch (InterruptedIOException ignored)
        {
        }
    }
}
