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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncContentTest
{
    // TODO make an OutputStreamContentSource version of this test

    @Test
    public void testWriteInvokesDemandCallback() throws Exception
    {
        try (AsyncContent async = new AsyncContent())
        {
            CountDownLatch latch = new CountDownLatch(1);
            async.demand(latch::countDown);
            assertFalse(latch.await(250, TimeUnit.MILLISECONDS));

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
        assertFalse(latch.await(250, TimeUnit.MILLISECONDS));

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
            assertFalse(latch.await(250, TimeUnit.MILLISECONDS));

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
            assertFalse(latch.await(250, TimeUnit.MILLISECONDS));

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

    @Test
    public void testChunkReleaseSucceedsWriteCallback()
    {
        try (AsyncContent async = new AsyncContent())
        {
            AtomicInteger successCounter = new AtomicInteger();
            AtomicReference<Throwable> failureRef = new AtomicReference<>();

            async.write(false, ByteBuffer.wrap(new byte[1]), Callback.from(successCounter::incrementAndGet, failureRef::set));

            Content.Chunk chunk = async.read();
            assertThat(successCounter.get(), is(0));
            chunk.retain();
            assertThat(chunk.release(), is(false));
            assertThat(successCounter.get(), is(0));
            assertThat(chunk.release(), is(true));
            assertThat(successCounter.get(), is(1));
            assertThat(failureRef.get(), is(nullValue()));
        }
    }

    @Test
    public void testEmptyChunkReadSucceedsWriteCallback()
    {
        try (AsyncContent async = new AsyncContent())
        {
            AtomicInteger successCounter = new AtomicInteger();
            AtomicReference<Throwable> failureRef = new AtomicReference<>();

            async.write(false, ByteBuffer.wrap(new byte[0]), Callback.from(successCounter::incrementAndGet, failureRef::set));

            Content.Chunk chunk = async.read();
            assertThat(successCounter.get(), is(1));
            assertThat(chunk.isTerminal(), is(false));
            assertThat(chunk.release(), is(true));
            assertThat(successCounter.get(), is(1));
            assertThat(failureRef.get(), is(nullValue()));
        }
    }

    @Test
    public void testLastEmptyChunkReadSucceedsWriteCallback()
    {
        try (AsyncContent async = new AsyncContent())
        {
            AtomicInteger successCounter = new AtomicInteger();
            AtomicReference<Throwable> failureRef = new AtomicReference<>();

            async.write(true, ByteBuffer.wrap(new byte[0]), Callback.from(successCounter::incrementAndGet, failureRef::set));

            Content.Chunk chunk = async.read();
            assertThat(successCounter.get(), is(1));
            assertThat(chunk.isTerminal(), is(true));
            assertThat(chunk.release(), is(true));
            assertThat(successCounter.get(), is(1));
            assertThat(failureRef.get(), is(nullValue()));
        }
    }

    @Test
    public void testWriteAndReadErrors()
    {
        try (AsyncContent async = new AsyncContent())
        {
            AssertingCallback callback = new AssertingCallback();

            Exception error1 = new Exception("error1");
            async.write(Content.Chunk.from(error1), callback);
            callback.assertSingleFailureSameInstanceNoSuccess(error1);

            Content.Chunk chunk = async.read();
            assertThat(((Content.Chunk.Error)chunk).getCause(), sameInstance(error1));
            chunk = async.read();
            assertThat(((Content.Chunk.Error)chunk).getCause(), sameInstance(error1));
            callback.assertNoFailureNoSuccess();

            Exception error2 = new Exception("error2");
            async.write(Content.Chunk.from(error2), callback);
            callback.assertSingleFailureSameInstanceNoSuccess(error1);

            async.write(Content.Chunk.from(ByteBuffer.wrap(new byte[1]), false), callback);
            callback.assertSingleFailureSameInstanceNoSuccess(error1);
        }
    }

    @Test
    public void testCloseAfterWritingEof()
    {
        AssertingCallback callback = new AssertingCallback();
        try (AsyncContent async = new AsyncContent())
        {
            async.write(Content.Chunk.EOF, callback);
            callback.assertNoFailureNoSuccess();

            Content.Chunk chunk = async.read();
            assertThat(chunk.isTerminal(), is(true));
            callback.assertNoFailureWithSuccesses(1);

            chunk = async.read();
            assertThat(chunk.isTerminal(), is(true));
            callback.assertNoFailureWithSuccesses(0);

            async.write(Content.Chunk.EOF, callback);
            callback.assertSingleFailureIsInstanceNoSuccess(IOException.class);
        }
        callback.assertNoFailureNoSuccess();
    }

    @Test
    public void testFailFailsCallbacks()
    {
        try (AsyncContent async = new AsyncContent())
        {
            AssertingCallback callback1 = new AssertingCallback();
            async.write(false, ByteBuffer.wrap(new byte[1]), callback1);
            AssertingCallback callback2 = new AssertingCallback();
            async.write(false, ByteBuffer.wrap(new byte[2]), callback2);
            AssertingCallback callback3 = new AssertingCallback();
            async.write(false, ByteBuffer.wrap(new byte[3]), callback3);

            Content.Chunk chunk = async.read();
            callback1.assertNoFailureNoSuccess();
            assertThat(chunk.getByteBuffer().remaining(), is(1));
            assertThat(chunk.release(), is(true));
            callback1.assertNoFailureWithSuccesses(1);

            Exception error1 = new Exception("test1");
            async.fail(error1);

            chunk = async.read();
            assertSame(error1, ((Content.Chunk.Error)chunk).getCause());

            callback2.assertSingleFailureSameInstanceNoSuccess(error1);
            callback3.assertSingleFailureSameInstanceNoSuccess(error1);
        }
    }

    @Test
    public void testWriteAfterFailImmediatelyFailsCallback()
    {
        try (AsyncContent async = new AsyncContent())
        {
            Exception error = new Exception("test1");
            async.fail(error);

            AssertingCallback callback = new AssertingCallback();
            async.write(false, ByteBuffer.wrap(new byte[1]), callback);
            callback.assertSingleFailureSameInstanceNoSuccess(error);
        }
    }

    private static class AssertingCallback implements Callback
    {
        private final AtomicInteger successCounter = new AtomicInteger();
        private final List<Throwable> throwables = new CopyOnWriteArrayList<>();

        @Override
        public void succeeded()
        {
            successCounter.incrementAndGet();
        }

        @Override
        public void failed(Throwable x)
        {
            throwables.add(x);
        }

        public void assertNoFailureNoSuccess()
        {
            assertThat(successCounter.get(), is(0));
            assertThat(throwables.isEmpty(), is(true));
        }

        public void assertNoFailureWithSuccesses(int successCount)
        {
            assertThat(successCounter.getAndSet(0), is(successCount));
            assertThat(throwables.isEmpty(), is(true));
        }

        public void assertSingleFailureSameInstanceNoSuccess(Throwable x)
        {
            assertThat(successCounter.get(), is(0));
            assertThat(throwables.size(), is(1));
            assertThat(throwables.remove(0), sameInstance(x));
        }

        public void assertSingleFailureIsInstanceNoSuccess(Class<? extends Throwable> clazz)
        {
            assertThat(successCounter.get(), is(0));
            assertThat(throwables.size(), is(1));
            assertThat(throwables.remove(0), instanceOf(clazz));
        }
    }
}
