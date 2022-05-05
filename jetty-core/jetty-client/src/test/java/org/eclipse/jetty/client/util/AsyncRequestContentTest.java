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

package org.eclipse.jetty.client.util;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncRequestContentTest
{
    private ExecutorService executor;

    @BeforeEach
    public void prepare()
    {
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    public void dispose()
    {
        executor.shutdownNow();
    }

    @Test
    public void testWhenEmptyFlushDoesNotBlock() throws Exception
    {
        AsyncRequestContent content = new AsyncRequestContent();

        Future<?> task = executor.submit(() ->
        {
            content.flush();
            return null;
        });

        assertTrue(await(task, 5000));
    }

    @Test
    public void testWriteFlushDemandFlushBlocksUntilRead() throws Exception
    {
        AsyncRequestContent content = new AsyncRequestContent();
        content.write(ByteBuffer.allocate(1), Callback.NOOP);

        Future<?> task = executor.submit(() ->
        {
            content.flush();
            return null;
        });

        // Wait until flush() blocks.
        assertFalse(await(task, 500));

        CountDownLatch demandLatch = new CountDownLatch(1);
        content.demand(demandLatch::countDown);
        assertTrue(demandLatch.await(5, TimeUnit.SECONDS));

        // Flush should block until a read() is performed.
        assertFalse(await(task, 500));

        Content.Chunk chunk = content.read();
        assertNotNull(chunk);

        // Flush should return.
        assertTrue(await(task, 5000));
    }

    @Test
    public void testCloseFlushDoesNotBlock() throws Exception
    {
        AsyncRequestContent content = new AsyncRequestContent();
        content.close();

        Future<?> task = executor.submit(() ->
        {
            content.flush();
            return null;
        });

        assertTrue(await(task, 5000));
    }

    @Test
    public void testStallThenCloseInvokesDemandCallback() throws Exception
    {
        AsyncRequestContent content = new AsyncRequestContent();

        CountDownLatch latch = new CountDownLatch(1);
        // Initial demand, there is no content, so we are stalled.
        content.demand(latch::countDown);
        assertFalse(latch.await(1, TimeUnit.SECONDS));

        // Close, demand callback must be notified.
        content.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    private boolean await(Future<?> task, long time) throws Exception
    {
        try
        {
            task.get(time, TimeUnit.MILLISECONDS);
            return true;
        }
        catch (TimeoutException x)
        {
            return false;
        }
    }
}
