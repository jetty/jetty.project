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

package org.eclipse.jetty.io;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.eclipse.jetty.io.content.OutputStreamContentSource;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContentTest
{
    @Test
    public void testFromEmptyByteBufferWithoutReleaser()
    {
        assertThat(Content.Chunk.from(ByteBuffer.wrap(new byte[0]), true), sameInstance(Content.Chunk.EOF));
        assertThat(Content.Chunk.from(ByteBuffer.wrap(new byte[0]), false), sameInstance(Content.Chunk.EMPTY));
    }

    @Test
    public void testFromEmptyByteBufferWithRetainableReleaser()
    {
        Retainable.ReferenceCounter rc1 = new Retainable.ReferenceCounter();
        rc1.retain();
        assertThat(rc1.isRetained(), is(true));
        assertEquals(2, rc1.getCount());

        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(ByteBuffer.allocate(0), rc1))
        {
            assertEquals(3, rc1.getCount());

            try (Content.Chunk chunk = Content.Chunk.from(buffer, true))
            {
                assertThat(chunk, sameInstance(Content.Chunk.EOF));
                assertEquals(3, rc1.getCount());
            }
            assertEquals(3, rc1.getCount());
        }
        assertEquals(2, rc1.getCount());

        // Pairs the initial retain().
        assertThat(rc1.release(), is(false));

        assertThat(rc1.isRetained(), is(false));
        assertThat(rc1.release(), is(true));

        Retainable.ReferenceCounter rc2 = new Retainable.ReferenceCounter();
        rc2.retain();
        assertThat(rc2.isRetained(), is(true));
        assertEquals(2, rc2.getCount());

        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(ByteBuffer.allocate(0), rc2))
        {
            assertEquals(3, rc2.getCount());

            try (Content.Chunk chunk = Content.Chunk.from(buffer, false))
            {
                assertThat(chunk, sameInstance(Content.Chunk.EMPTY));
                assertEquals(3, rc2.getCount());
            }
            assertEquals(3, rc2.getCount());
        }
        assertEquals(2, rc2.getCount());

        // Pairs the initial retain().
        assertThat(rc2.release(), is(false));

        assertThat(rc2.isRetained(), is(false));
        assertThat(rc2.release(), is(true));
    }

    @Test
    public void testCopyDoesNotReleaseChunkBeforeSinkWriteCompletes() throws Exception
    {
        testApplicationReusesByteArray((source, sink) -> Content.copy(source, sink, Callback.NOOP));
    }

    @Test
    public void testSubscriberDoesNotReleaseChunkBeforeSinkWriteCompletes() throws Exception
    {
        testApplicationReusesByteArray((source, sink) ->
            Content.Source.asPublisher(source).subscribe(Content.Sink.asSubscriber(sink, Callback.NOOP)));
    }

    @Test
    public void testFailFromCollectionReleasesUnreadBuffers()
    {
        List<Retainable.ReferenceCounter> counters = List.of(
            new Retainable.ReferenceCounter(),
            new Retainable.ReferenceCounter(),
            new Retainable.ReferenceCounter()
        );
        List<RetainableByteBuffer> buffers = counters.stream()
            .map(counter -> RetainableByteBuffer.wrap(ByteBuffer.wrap(new byte[]{1}), counter))
            .toList();
        // Pairs the ReferenceCounter constructor, wrap() retained.
        counters.forEach(Retainable.ReferenceCounter::release);
        Content.Source source = Content.Source.from(buffers);
        // The source retained the buffers, release the original references.
        buffers.forEach(RetainableByteBuffer::release);
        counters.forEach(counter -> assertEquals(1, counter.getCount()));

        try (Content.Chunk chunk = source.read())
        {
            assertNotNull(chunk);
        }
        assertEquals(0, counters.get(0).getCount());
        assertEquals(1, counters.get(1).getCount());
        assertEquals(1, counters.get(2).getCount());

        source.fail(new Throwable("test_fail"));
        assertTrue(Content.Chunk.isFailure(source.read(), true));

        counters.forEach(counter -> assertEquals(0, counter.getCount()));
    }

    private void testApplicationReusesByteArray(BiConsumer<Content.Source, Content.Sink> copier) throws Exception
    {
        // A sink whose writes do not complete immediately,
        // like a network write when the TCP buffer is full.
        BlockingQueue<PendingWrite> pending = new LinkedBlockingQueue<>();
        Content.Sink slowSink = (last, buffer, callback) ->
        {
            buffer.retain();
            pending.offer(new PendingWrite(buffer, callback));
        };

        OutputStreamContentSource source = new OutputStreamContentSource();
        copier.accept(source, slowSink);

        // The application writes reusing the same byte[],
        // which is allowed once OutputStream.write() returns.
        CountDownLatch firstWriteReturned = new CountDownLatch(1);
        CompletableFuture<Void> application = new CompletableFuture<>();
        Thread.ofPlatform().start(() ->
        {
            try (OutputStream output = source.getOutputStream())
            {
                byte[] bytes = new byte[4];
                Arrays.fill(bytes, (byte)'A');
                output.write(bytes, 0, bytes.length);
                firstWriteReturned.countDown();
                Arrays.fill(bytes, (byte)'B');
                output.write(bytes, 0, bytes.length);
                application.complete(null);
            }
            catch (Throwable x)
            {
                application.completeExceptionally(x);
            }
        });

        PendingWrite first = pending.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);

        // OutputStream.write() must not return while the sink write is pending.
        assertFalse(firstWriteReturned.await(500, TimeUnit.MILLISECONDS));
        assertEquals("AAAA", first.buffer().getString(StandardCharsets.US_ASCII));
        first.buffer().release();
        first.callback().succeeded();

        // Completing the sink write releases the application.
        assertTrue(firstWriteReturned.await(5, TimeUnit.SECONDS));

        PendingWrite second = pending.poll(5, TimeUnit.SECONDS);
        assertNotNull(second);
        assertEquals("BBBB", second.buffer().getString(StandardCharsets.US_ASCII));
        second.buffer().release();
        second.callback().succeeded();

        // Complete the last write, triggered by closing the OutputStream.
        PendingWrite last = pending.poll(5, TimeUnit.SECONDS);
        assertNotNull(last);
        last.buffer().release();
        last.callback().succeeded();

        application.get(5, TimeUnit.SECONDS);
    }

    private record PendingWrite(RetainableByteBuffer buffer, Callback callback)
    {
    }
}
