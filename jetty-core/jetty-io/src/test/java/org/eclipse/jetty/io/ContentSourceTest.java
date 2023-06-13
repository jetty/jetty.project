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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.io.content.ContentSourceInputStream;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.io.content.PathContentSource;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class ContentSourceTest
{
    public static List<Content.Source> all() throws Exception
    {
        AsyncContent asyncSource = new AsyncContent();
        try (asyncSource)
        {
            asyncSource.write(false, UTF_8.encode("one"), Callback.NOOP);
            asyncSource.write(false, UTF_8.encode("two"), Callback.NOOP);
        }

        ByteBufferContentSource byteBufferSource = new ByteBufferContentSource(UTF_8.encode("one"), UTF_8.encode("two"));

        ContentSourceTransformer transformerSource = new ContentSourceTransformer(new ByteBufferContentSource(UTF_8.encode("one"), UTF_8.encode("two")))
        {
            @Override
            protected Content.Chunk transform(Content.Chunk rawChunk)
            {
                return rawChunk;
            }

            @Override
            public String toString()
            {
                return "%s@%x".formatted(ContentSourceTransformer.class.getSimpleName(), hashCode());
            }
        };

        Path tmpDir = MavenTestingUtils.getTargetTestingPath();
        Files.createDirectories(tmpDir);
        Path path = Files.createTempFile(tmpDir, ContentSourceTest.class.getSimpleName(), ".txt");
        Files.writeString(path, "onetwo", StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        PathContentSource pathSource = new PathContentSource(path);
        pathSource.setBufferSize(3);

        InputStreamContentSource inputSource = new InputStreamContentSource(new ByteArrayInputStream("onetwo".getBytes(UTF_8)));

        InputStreamContentSource inputSource2 =
            new InputStreamContentSource(new ContentSourceInputStream(new ByteBufferContentSource(UTF_8.encode("one"), UTF_8.encode("two"))));

        return List.of(asyncSource, byteBufferSource, transformerSource, pathSource, inputSource, inputSource2);
    }

    /** Get the next chunk, blocking if necessary
     * @param source The source to get the next chunk from
     * @return A non null chunk
     */
    public static Content.Chunk nextChunk(Content.Source source)
    {
        Content.Chunk chunk = source.read();
        if (chunk != null)
            return chunk;
        FuturePromise<Content.Chunk> next = new FuturePromise<>();
        Runnable getNext = new Runnable()
        {
            @Override
            public void run()
            {
                Content.Chunk chunk = source.read();
                if (chunk == null)
                    source.demand(this);
                next.succeeded(chunk);
            }
        };
        source.demand(getNext);
        try
        {
            return next.get();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testRead(Content.Source source) throws Exception
    {
        StringBuilder builder = new StringBuilder();
        CountDownLatch eof = new CountDownLatch(1);
        source.demand(new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk chunk = source.read();
                    if (chunk == null)
                    {
                        source.demand(this);
                        break;
                    }

                    if (chunk.hasRemaining())
                        builder.append(BufferUtil.toString(chunk.getByteBuffer()));
                    chunk.release();

                    if (chunk.isLast())
                    {
                        eof.countDown();
                        break;
                    }
                }
            }
        });

        assertTrue(eof.await(10, TimeUnit.SECONDS));
        assertThat(builder.toString(), is("onetwo"));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testDemandReadDemandDoesNotRecurse(Content.Source source) throws Exception
    {
        CountDownLatch processed = new CountDownLatch(1);
        AtomicBoolean recursion = new AtomicBoolean();
        source.demand(new Runnable()
        {
            @Override
            public void run()
            {
                processed.countDown();

                assertTrue(recursion.compareAndSet(false, true));

                Content.Chunk chunk = source.read();
                assertNotNull(chunk);
                chunk.release();

                // Demand again, it must not recurse.
                if (!chunk.isLast())
                    source.demand(this);

                assertTrue(recursion.compareAndSet(true, false));
            }
        });
        assertTrue(processed.await(10, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testDemandDemandThrows(Content.Source source) throws Exception
    {
        CountDownLatch processed = new CountDownLatch(1);
        source.demand(new Runnable()
        {
            @Override
            public void run()
            {
                Content.Chunk chunk = source.read();
                assertNotNull(chunk);
                chunk.release();

                if (!chunk.isLast())
                {
                    // First demand is ok.
                    source.demand(this);
                    // Second demand after the first must throw.
                    assertThrows(IllegalStateException.class, () -> source.demand(this));
                }
                processed.countDown();
            }
        });
        assertTrue(processed.await(10, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testReadFailReadReturnsError(Content.Source source) throws Exception
    {
        Content.Chunk chunk = nextChunk(source);
        assertNotNull(chunk);
        chunk.release();

        source.fail(new CancellationException());

        // We must read the error.
        chunk = source.read();
        assertTrue(Content.Chunk.isError(chunk));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testReadLastDemandInvokesDemandCallback(Content.Source source) throws Exception
    {
        Content.Source.consumeAll(source);

        CountDownLatch latch = new CountDownLatch(1);
        source.demand(latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testReadErrorDemandInvokesDemandCallback(Content.Source source) throws Exception
    {
        source.fail(new CancellationException());

        Content.Chunk chunk = source.read();
        assertTrue(Content.Chunk.isError(chunk));

        CountDownLatch latch = new CountDownLatch(1);
        source.demand(latch::countDown);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("all")
    public void testDemandCallbackThrows(Content.Source source) throws Exception
    {
        // TODO fix for OSCS
//        if (source instanceof OutputStreamContentSource)
//            return;

        Content.Chunk chunk = nextChunk(source);
        assertNotNull(chunk);
        chunk.release();

        source.demand(() ->
        {
            throw new CancellationException();
        });

        chunk = source.read();
        assertTrue(Content.Chunk.isError(chunk));
    }

    @Test
    public void testSimple()
    {
        TestContentSource source = new TestContentSource();
        assertNull(source.read());
        source.add("hello", false);
        Content.Chunk chunk = source.read();
        assertNotNull(chunk);
        assertThat(UTF_8.decode(chunk.getByteBuffer()).toString(), equalTo("hello"));
        chunk.release();
    }

    @Test
    public void testReadBytes() throws Exception
    {
        TestContentSource source = new TestContentSource();

        FuturePromise<ByteBuffer> promise = new FuturePromise<>();
        Content.Source.asByteBuffer(source, promise);

        Runnable todo = source.takeDemand();
        assertNotNull(todo);
        source.add("hello", false);
        todo.run();
        assertFalse(promise.isDone());

        todo = source.takeDemand();
        assertNotNull(todo);
        source.add(" cruel", false);
        source.add(" world", true);
        todo.run();

        todo = source.takeDemand();
        assertNull(todo);
        assertTrue(promise.isDone());
        ByteBuffer output = promise.get(10, TimeUnit.SECONDS);
        assertNotNull(output);
        assertThat(BufferUtil.toString(output), equalTo("hello cruel world"));
    }

    @Test
    public void testReadUTF8() throws Exception
    {
        TestContentSource source = new TestContentSource();

        FuturePromise<String> promise = new FuturePromise<>();
        Content.Source.asString(source, UTF_8, promise);

        Runnable todo = source.takeDemand();
        assertNotNull(todo);
        source.add("hello", false);
        todo.run();
        assertFalse(promise.isDone());

        todo = source.takeDemand();
        assertNotNull(todo);
        source.add(" cruel", false);
        source.add(" world", true);
        todo.run();

        todo = source.takeDemand();
        assertNull(todo);
        assertTrue(promise.isDone());
        String output = promise.get(10, TimeUnit.SECONDS);
        assertNotNull(output);
        assertThat(output, equalTo("hello cruel world"));
    }

    @Test
    public void testConsumeAll() throws Exception
    {
        TestContentSource source = new TestContentSource();

        FutureCallback callback = new FutureCallback();
        Content.Source.consumeAll(source, callback);
        Runnable todo = source.takeDemand();
        assertNotNull(todo);
        source.add("hello", false);
        todo.run();
        assertFalse(callback.isDone());

        todo = source.takeDemand();
        assertNotNull(todo);
        source.add(" cruel", false);
        source.add(" world", true);
        todo.run();

        todo = source.takeDemand();
        assertNull(todo);
        assertTrue(callback.isDone());
        callback.get();
    }

    @Test
    public void testConsumeAllFailed()
    {
        TestContentSource source = new TestContentSource();

        FutureCallback callback = new FutureCallback();
        Content.Source.consumeAll(source, callback);
        Runnable todo = source.takeDemand();
        assertNotNull(todo);
        source.add("hello", false);
        todo.run();
        assertFalse(callback.isDone());

        todo = source.takeDemand();
        assertNotNull(todo);

        Throwable cause = new Throwable("test cause");
        source.add(Content.Chunk.from(cause));
        todo.run();

        todo = source.takeDemand();
        assertNull(todo);
        assertTrue(callback.isDone());
        assertThrows(ExecutionException.class, callback::get);
    }

    @Test
    public void testInputStream() throws Exception
    {
        TestContentSource source = new TestContentSource();

        InputStream in = Content.Source.asInputStream(source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicReference<Throwable> throwable = new AtomicReference<>();
        CountDownLatch complete = new CountDownLatch(1);
        new Thread(() ->
        {
            try
            {
                IO.copy(in, out);
            }
            catch (Throwable t)
            {
                throwable.set(t);
            }
            finally
            {
                complete.countDown();
            }
        }).start();

        long wait = System.currentTimeMillis() + 1000;
        Runnable todo = source.takeDemand();
        while (todo == null && System.currentTimeMillis() < wait)
        {
            todo = source.takeDemand();
        }
        assertNotNull(todo);
        source.add("hello", false);
        todo.run();

        wait = System.currentTimeMillis() + 1000;
        todo = source.takeDemand();
        while (todo == null && System.currentTimeMillis() < wait)
        {
            todo = source.takeDemand();
        }
        assertNotNull(todo);

        source.add(" cruel", false);
        source.add(" world", true);
        todo.run();
        assertTrue(complete.await(10, TimeUnit.SECONDS));

        assertNull(throwable.get());
        assertThat(out.toString(UTF_8), equalTo("hello cruel world"));
    }

    @Test
    public void testInputStreamFailed() throws Exception
    {
        TestContentSource source = new TestContentSource();

        InputStream in = Content.Source.asInputStream(source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicReference<Throwable> throwable = new AtomicReference<>();
        CountDownLatch complete = new CountDownLatch(1);
        new Thread(() ->
        {
            try
            {
                IO.copy(in, out);
            }
            catch (Throwable t)
            {
                throwable.set(t);
            }
            finally
            {
                complete.countDown();
            }
        }).start();

        long wait = System.currentTimeMillis() + 1000;
        Runnable todo = source.takeDemand();
        while (todo == null && System.currentTimeMillis() < wait)
        {
            todo = source.takeDemand();
        }
        assertNotNull(todo);
        source.add("hello", false);
        todo.run();

        wait = System.currentTimeMillis() + 1000;
        todo = source.takeDemand();
        while (todo == null && System.currentTimeMillis() < wait)
        {
            todo = source.takeDemand();
        }
        assertNotNull(todo);

        Throwable cause = new Throwable("test cause");
        source.add(Content.Chunk.from(cause));
        todo.run();

        assertTrue(complete.await(10, TimeUnit.SECONDS));

        assertNotNull(throwable.get());
        assertThat(out.toString(UTF_8), equalTo("hello"));
    }

    private static class TestContentSource implements Content.Source
    {
        private final AtomicReference<Runnable> _demand = new AtomicReference<>();
        private final Deque<Content.Chunk> _chunks = new ConcurrentLinkedDeque<>();

        private Runnable takeDemand()
        {
            return _demand.getAndSet(null);
        }

        private void add(Content.Chunk chunk)
        {
            // Retain the chunk because it is stored for later use.
            chunk.retain();
            _chunks.add(chunk);
        }

        private void add(String content, boolean last)
        {
            ByteBuffer buffer = UTF_8.encode(content);
            add(Content.Chunk.from(buffer, last));
        }

        @Override
        public Content.Chunk read()
        {
            Content.Chunk chunk = _chunks.poll();
            Content.Chunk next = Content.Chunk.next(chunk);
            if (next != null)
            {
                _chunks.clear();
                _chunks.add(next);
            }
            return chunk;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (!_demand.compareAndSet(null, demandCallback))
                throw new IllegalStateException();
        }

        @Override
        public void fail(Throwable failure)
        {
        }
    }

    @Test
    public void testAsyncContentWithWarnings()
    {
        AsyncContent content = new AsyncContent();

        Content.Sink.write(content, false, "One", Callback.NOOP);
        content.warn(new TimeoutException("test"));
        Content.Sink.write(content, true, "Two", Callback.NOOP);

        Content.Chunk chunk = content.read();
        assertFalse(chunk.isLast());
        assertFalse(Content.Chunk.isError(chunk));
        assertThat(BufferUtil.toString(chunk.getByteBuffer()), is("One"));

        chunk = content.read();
        assertFalse(chunk.isLast());
        assertTrue(Content.Chunk.isError(chunk));
        assertThat(chunk.getCause(), instanceOf(TimeoutException.class));

        chunk = content.read();
        assertTrue(chunk.isLast());
        assertFalse(Content.Chunk.isError(chunk));
        assertThat(BufferUtil.toString(chunk.getByteBuffer()), is("Two"));
    }

    @Test
    public void testAsyncContentWithWarningsAsInputStream() throws Exception
    {
        AsyncContent content = new AsyncContent();

        Content.Sink.write(content, false, "One", Callback.NOOP);
        content.warn(new TimeoutException("test"));
        Content.Sink.write(content, true, "Two", Callback.NOOP);

        InputStream in = Content.Source.asInputStream(content);

        byte[] buffer = new byte[1024];
        int len = in.read(buffer);
        assertThat(len, is(3));
        assertThat(new String(buffer, 0, 3, StandardCharsets.ISO_8859_1), is("One"));

        try
        {
            int ignored = in.read();
            fail();
        }
        catch (IOException ioe)
        {
            assertThat(ioe.getCause(), instanceOf(TimeoutException.class));
        }

        len = in.read(buffer);
        assertThat(len, is(3));
        assertThat(new String(buffer, 0, 3, StandardCharsets.ISO_8859_1), is("Two"));

        len = in.read(buffer);
        assertThat(len, is(-1));
    }
}
