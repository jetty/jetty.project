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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.CompletionHandler;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import org.eclipse.jetty.io.content.OutputStreamContentSource;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.ArrayByteBufferPool;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class ContentTest
{
    public WorkDir workDir;

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

    public static Stream<Arguments> asyncChannelWrites()
    {
        return Stream.of("single", "path", "multi")
            .flatMap(type -> Stream.of(true, false)
                .map(b -> Arguments.of(type, b)));
    }

    @ParameterizedTest
    @MethodSource("asyncChannelWrites")
    public void testAsynchronousByteChannelSink(String type, boolean inline) throws Exception
    {
        ArrayByteBufferPool.Tracking pool = new ArrayByteBufferPool.Tracking();
        ByteBufferPool.Sized sizedPool = new ByteBufferPool.Sized(pool, false, 1024);
        byte[] content = new byte[8 * sizedPool.getSize() + 123];
        ThreadLocalRandom.current().nextBytes(content);

        ExecutorService executor = inline ? null : Executors.newSingleThreadExecutor();
        try
        {
            // Partial writes that also do not align with the pool buffers.
            TestAsynchronousByteChannel channel = new TestAsynchronousByteChannel(executor, 700, content.length);
            RetainableByteBuffer buffer = newBuffer(type, content, sizedPool);
            Callback.Completable callback = new Callback.Completable();

            Content.Sink.from(channel).write(true, buffer, callback);
            buffer.release();

            callback.get(5, TimeUnit.SECONDS);
            assertArrayEquals(content, channel.toByteArray());
            assertFalse(channel.isOpen());
            assertEquals(0, pool.getLeaks().size(), pool::dumpLeaks);
        }
        finally
        {
            if (executor != null)
                executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    public void testAsynchronousByteChannelSinkFailureReleasesBuffer(int writesBeforeThrow) throws Exception
    {
        ArrayByteBufferPool.Tracking pool = new ArrayByteBufferPool.Tracking();
        ByteBufferPool.Sized sizedPool = new ByteBufferPool.Sized(pool, false, 1024);
        byte[] content = new byte[4 * sizedPool.getSize()];

        TestAsynchronousByteChannel channel = new TestAsynchronousByteChannel(null, Integer.MAX_VALUE, content.length);
        channel.throwAfterWrites = writesBeforeThrow;
        RetainableByteBuffer buffer = newBuffer("path", content, sizedPool);
        Callback.Completable callback = new Callback.Completable();

        Content.Sink.from(channel).write(false, buffer, callback);

        assertThrows(ExecutionException.class, () -> callback.get(5, TimeUnit.SECONDS));
        // The Sink must have released the buffer it retained.
        assertFalse(buffer.isRetained());
        buffer.release();
        assertEquals(0, pool.getLeaks().size(), pool::dumpLeaks);
    }

    private RetainableByteBuffer newBuffer(String type, byte[] content, ByteBufferPool.Sized pool) throws IOException
    {
        return switch (type)
        {
            case "single" -> RetainableByteBuffer.wrap(ByteBuffer.wrap(content));
            case "path" -> RetainableByteBuffer.wrap(newFile(content), pool);
            case "multi" ->
            {
                // A file between in-memory buffers, as the Reader must stop
                // at each of them until the channel write completes.
                int head = 100;
                int tail = 200;
                Path file = newFile(Arrays.copyOfRange(content, head, content.length - tail));
                try (RetainableByteBuffer b1 = RetainableByteBuffer.wrap(ByteBuffer.wrap(content, 0, head));
                     RetainableByteBuffer b2 = RetainableByteBuffer.wrap(file, pool);
                     RetainableByteBuffer b3 = RetainableByteBuffer.wrap(ByteBuffer.wrap(content, content.length - tail, tail)))
                {
                    yield RetainableByteBuffer.merge(List.of(b1, b2, b3));
                }
            }
            default -> throw new IllegalArgumentException(type);
        };
    }

    private Path newFile(byte[] content) throws IOException
    {
        return Files.write(workDir.getEmptyPathDir().resolve("content.bin"), content);
    }

    private record PendingWrite(RetainableByteBuffer buffer, Callback callback)
    {
    }

    private static class TestAsynchronousByteChannel implements AsynchronousByteChannel
    {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final Executor executor;
        private final int maxWrite;
        private final int maxTotal;
        private int throwAfterWrites = -1;
        private boolean closed;

        private TestAsynchronousByteChannel(Executor executor, int maxWrite, int maxTotal)
        {
            this.executor = executor;
            this.maxWrite = maxWrite;
            this.maxTotal = maxTotal;
        }

        private byte[] toByteArray()
        {
            return output.toByteArray();
        }

        @Override
        public <A> void write(ByteBuffer src, A attachment, CompletionHandler<Integer, ? super A> handler)
        {
            if (throwAfterWrites == 0)
                throw new WritePendingException();
            if (throwAfterWrites > 0)
                --throwAfterWrites;

            int length = Math.min(src.remaining(), maxWrite);
            for (int i = 0; i < length; ++i)
            {
                output.write(src.get());
            }

            Runnable completion = output.size() > maxTotal
                ? () -> handler.failed(new IOException("too many bytes written"), attachment)
                : () -> handler.completed(length, attachment);
            if (executor == null)
                completion.run();
            else
                executor.execute(completion);
        }

        @Override
        public Future<Integer> write(ByteBuffer src)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public <A> void read(ByteBuffer dst, A attachment, CompletionHandler<Integer, ? super A> handler)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Future<Integer> read(ByteBuffer dst)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen()
        {
            return !closed;
        }

        @Override
        public void close()
        {
            closed = true;
        }
    }
}
