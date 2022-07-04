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

package org.eclipse.jetty.io;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncContentSourceTest
{
    @Test
    public void testOfferInvokesDemandCallback() throws Exception
    {
        try (AsyncContent async = new AsyncContent())
        {
            CountDownLatch latch = new CountDownLatch(1);
            async.demand(latch::countDown);
            assertFalse(latch.await(100, TimeUnit.MILLISECONDS));

            async.write(Content.Chunk.from(UTF_8.encode("one"), false), Callback.NOOP);

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            Content.Chunk chunk = async.read();
            assertNotNull(chunk);
        }
    }

    @Test
    public void testCloseInvokesDemandCallback() throws Exception
    {
        AsyncContent async = new AsyncContent();

        CountDownLatch latch = new CountDownLatch(1);
        async.demand(latch::countDown);
        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));

        async.close();

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        Content.Chunk chunk = async.read();
        assertNotNull(chunk);
        assertTrue(chunk.isLast());
    }

    @Test
    public void testFailInvokesDemandCallback() throws Exception
    {
        try (AsyncContent async = new AsyncContent())
        {
            async.write(Content.Chunk.from(UTF_8.encode("one"), false), Callback.NOOP);

            Content.Chunk chunk = async.read();
            assertNotNull(chunk);

            CountDownLatch latch = new CountDownLatch(1);
            async.demand(latch::countDown);
            assertFalse(latch.await(100, TimeUnit.MILLISECONDS));

            async.fail(new CancellationException());

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            // We must read the error.
            chunk = async.read();
            assertInstanceOf(Content.Chunk.Error.class, chunk);

            // Offering more should fail.
            CountDownLatch failLatch = new CountDownLatch(1);
            async.write(Content.Chunk.EMPTY, Callback.from(Callback.NOOP::succeeded, x -> failLatch.countDown()));
            assertTrue(failLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testWriteErrorChunk() throws Exception
    {
        try (AsyncContent async = new AsyncContent())
        {
            CountDownLatch latch = new CountDownLatch(1);
            async.demand(latch::countDown);
            assertFalse(latch.await(100, TimeUnit.MILLISECONDS));

            Throwable error = new Throwable("test");
            AtomicReference<Throwable> callback = new AtomicReference<>();
            async.write(Content.Chunk.from(error), Callback.from(Invocable.NOOP, callback::set));

            assertThat(callback.get(), sameInstance(error));
            assertTrue(latch.await(5, TimeUnit.SECONDS));

            Content.Chunk chunk = async.read();
            assertNotNull(chunk);
            assertThat(chunk, instanceOf(Content.Chunk.Error.class));
            assertThat(((Content.Chunk.Error)chunk).getCause(), sameInstance(error));
        }
    }
}
