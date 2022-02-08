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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    public void testOfferFlushDemandBlocksUntilSucceeded() throws Exception
    {
        AsyncRequestContent content = new AsyncRequestContent();
        content.offer(ByteBuffer.allocate(1));

        Future<?> task = executor.submit(() ->
        {
            content.flush();
            return null;
        });

        // Wait until flush() blocks.
        assertFalse(await(task, 500));

        AtomicReference<Callback> callbackRef = new AtomicReference<>();
        content.subscribe((buffer, last, callback) -> callbackRef.set(callback), true).demand();

        // Flush should block until the callback is succeeded.
        assertFalse(await(task, 500));

        callbackRef.get().succeeded();

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
    public void testStallThenCloseProduces() throws Exception
    {
        AsyncRequestContent content = new AsyncRequestContent();

        CountDownLatch latch = new CountDownLatch(1);
        Request.Content.Subscription subscription = content.subscribe((buffer, last, callback) ->
        {
            callback.succeeded();
            if (last)
                latch.countDown();
        }, true);

        // Demand the initial content.
        subscription.demand();

        // Content must not be the last one.
        assertFalse(latch.await(1, TimeUnit.SECONDS));

        // Demand more content, now we are stalled.
        subscription.demand();

        // Close, we must be notified.
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
