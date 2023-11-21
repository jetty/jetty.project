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

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 10)
public class BlockingTest
{
    final Blocker.Shared _shared = new Blocker.Shared();

    Thread main;

    @BeforeEach
    public void setUp()
    {
        main = Thread.currentThread();
    }

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
        try (Blocker.Runnable runnable = Blocker.runnable();
             AssertingThread thread = new AssertingThread(() ->
             {
                 await().atMost(5, TimeUnit.SECONDS).until(main::getState, Matchers.is(Thread.State.WAITING));
                 runnable.run();
             }))
        {
            thread.start();
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
        try (Blocker.Callback callback = Blocker.callback();
             AssertingThread thread = new AssertingThread(() ->
             {
                 await().atMost(5, TimeUnit.SECONDS).until(main::getState, Matchers.is(Thread.State.WAITING));
                 callback.succeeded();
             }))
        {
            thread.start();
            callback.block();
        }
    }

    @Test
    public void testFailedBlock()
    {
        Exception ex = new Exception("FAILED");
        IOException actual = assertThrows(IOException.class, () ->
        {
            try (Blocker.Callback callback = Blocker.callback())
            {
                callback.failed(ex);
                callback.block();
            }
        });
        assertSame(ex, actual.getCause());
    }

    @Test
    public void testBlockFailed()
    {
        Exception ex = new Exception("FAILED");
        IOException actual = assertThrows(IOException.class, () ->
        {
            try (Blocker.Callback callback = Blocker.callback();
                 AssertingThread thread = new AssertingThread(() ->
                 {
                     await().atMost(5, TimeUnit.SECONDS).until(main::getState, Matchers.is(Thread.State.WAITING));
                     callback.failed(ex);
                 }))
            {
                thread.start();
                callback.block();
            }
        });
        assertSame(ex, actual.getCause());
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
        try (Blocker.Runnable runnable = _shared.runnable();
             AssertingThread thread = new AssertingThread(() ->
             {
                 await().atMost(5, TimeUnit.SECONDS).until(main::getState, Matchers.is(Thread.State.WAITING));
                 runnable.run();
             }))
        {
            thread.start();
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
        try (Blocker.Callback callback = _shared.callback();
             AssertingThread thread = new AssertingThread(() ->
             {
                 await().atMost(5, TimeUnit.SECONDS).until(main::getState, Matchers.is(Thread.State.WAITING));
                 callback.succeeded();
             }))
        {
            thread.start();
            callback.block();
        }
    }

    @Test
    public void testSharedFailedBlock()
    {
        Exception ex = new Exception("FAILED");
        IOException actual = assertThrows(IOException.class, () ->
        {
            try (Blocker.Callback callback = _shared.callback())
            {
                callback.failed(ex);
                callback.block();
            }
        });
        assertSame(ex, actual.getCause());
    }

    @Test
    public void testSharedBlockFailed()
    {
        Exception ex = new Exception("FAILED");
        IOException actual = assertThrows(IOException.class, () ->
        {
            try (Blocker.Callback callback = _shared.callback();
                 AssertingThread thread = new AssertingThread(() ->
                 {
                     await().atMost(5, TimeUnit.SECONDS).until(main::getState, Matchers.is(Thread.State.WAITING));
                     callback.failed(ex);
                 }))
            {
                thread.start();
                callback.block();
            }
        });
        assertSame(ex, actual.getCause());
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
        Blocker.Callback callback = _shared.callback();
        Thread.currentThread().interrupt();
        assertThrows(InterruptedIOException.class, callback::block);
    }

    private static class AssertingThread extends Thread implements Closeable
    {
        private Throwable failure;

        public AssertingThread(Runnable target)
        {
            super(target);
        }

        @Override
        public void close() throws IOException
        {
            try
            {
                join();
            }
            catch (InterruptedException e)
            {
                if (failure != null)
                    failure.addSuppressed(e);
                else
                    failure = e;
            }
            if (failure != null)
                throw new IOException(failure);
        }

        @Override
        public final void run()
        {
            try
            {
                super.run();
            }
            catch (Throwable x)
            {
                failure = x;
            }
        }
    }
}
