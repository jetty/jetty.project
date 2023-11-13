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
        try (Blocker.Runnable runnable = Blocker.runnable())
        {
            runnable.run();
            runnable.block();
        }
    }

    @Test
    public void testBlockRun() throws Exception
    {
        try (Blocker.Runnable runnable = Blocker.runnable())
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            new Thread(() ->
            {
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                    runnable.run();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            barrier.await(5, TimeUnit.SECONDS);
            runnable.block();
        }
    }

    @Test
    public void testNoRun()
    {
        try (Blocker.Runnable ignored = Blocker.runnable())
        {
            LoggerFactory.getLogger(Blocker.class).info("expect WARN Blocking.Runnable incomplete");
        }
    }

    @Test
    public void testSucceededBlock() throws Exception
    {
        try (Blocker.Callback callback = Blocker.callback())
        {
            callback.succeeded();
            callback.block();
        }
    }

    @Test
    public void testBlockSucceeded() throws Exception
    {
        try (Blocker.Callback callback = Blocker.callback())
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            new Thread(() ->
            {
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                    callback.succeeded();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            barrier.await(5, TimeUnit.SECONDS);
            callback.block();
        }
    }

    @Test
    public void testFailedBlock()
    {
        Exception ex = new Exception("FAILED");
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
            assertEquals(ex, e.getCause());
        }
    }

    @Test
    public void testBlockFailed() throws Exception
    {
        Exception ex = new Exception("FAILED");
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
                        callback.failed(ex);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }).start();

                barrier.await(5, TimeUnit.SECONDS);
                callback.block();
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            assertEquals(ex, e.getCause());
        }
    }

    @Test
    public void testSharedRunBlock() throws Exception
    {
        try (Blocker.Runnable runnable = _shared.runnable())
        {
            runnable.run();
            runnable.block();
        }
    }

    @Test
    public void testSharedBlockRun() throws Exception
    {
        try (Blocker.Runnable runnable = _shared.runnable())
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            new Thread(() ->
            {
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                    runnable.run();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            barrier.await(5, TimeUnit.SECONDS);
            runnable.block();
        }
    }

    @Test
    public void testSharedNoRun() throws Exception
    {
        try (Blocker.Runnable ignored = _shared.runnable())
        {
            LoggerFactory.getLogger(Blocker.class).info("expect WARN Blocking.Shared incomplete");
        }

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
        try (Blocker.Callback callback = _shared.callback())
        {
            callback.succeeded();
            callback.block();
        }
    }

    @Test
    public void testSharedBlockSucceeded() throws Exception
    {
        try (Blocker.Callback callback = _shared.callback())
        {
            CyclicBarrier barrier = new CyclicBarrier(2);
            new Thread(() ->
            {
                try
                {
                    barrier.await(5, TimeUnit.SECONDS);
                    callback.succeeded();
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }).start();

            barrier.await(5, TimeUnit.SECONDS);
            callback.block();
        }
    }

    @Test
    public void testSharedFailedBlock()
    {
        Exception ex = new Exception("FAILED");
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
            assertEquals(ex, e.getCause());
        }
    }

    @Test
    public void testSharedBlockFailed() throws Exception
    {
        Exception ex = new Exception("FAILED");
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
                        callback.failed(ex);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                }).start();

                barrier.await(5, TimeUnit.SECONDS);
                callback.block();
            }
            fail("Should have thrown IOException");
        }
        catch (IOException e)
        {
            assertEquals(ex, e.getCause());
        }
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
}
