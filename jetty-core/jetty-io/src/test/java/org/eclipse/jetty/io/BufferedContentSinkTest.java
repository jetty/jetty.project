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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.io.content.BufferedContentSink;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BufferedContentSinkTest
{
    private ArrayByteBufferPool.Tracking _bufferPool;

    @BeforeEach
    public void setUp()
    {
        _bufferPool = new ArrayByteBufferPool.Tracking();
    }

    @AfterEach
    public void tearDown()
    {
        assertThat("Leaks: " + _bufferPool.dumpLeaks(), _bufferPool.getLeaks().size(), is(0));
    }

    @Test
    public void testConstructor()
    {
        assertThrows(IllegalArgumentException.class, () -> new BufferedContentSink(new AsyncContent(), _bufferPool, true, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BufferedContentSink(new AsyncContent(), _bufferPool, true, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BufferedContentSink(new AsyncContent(), _bufferPool, true, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new BufferedContentSink(new AsyncContent(), _bufferPool, true, 1, -1));
        assertThrows(IllegalArgumentException.class, () -> new BufferedContentSink(new AsyncContent(), _bufferPool, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new BufferedContentSink(new AsyncContent(), _bufferPool, true, -1, -1));
        assertThrows(IllegalArgumentException.class, () -> new BufferedContentSink(new AsyncContent(), _bufferPool, true, 1, 2));
    }

    @Test
    public void testWriteInvokesDemandCallback() throws Exception
    {
        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, 4096, 4096);

            CountDownLatch latch = new CountDownLatch(1);
            async.demand(latch::countDown);
            assertFalse(latch.await(250, TimeUnit.MILLISECONDS));

            buffered.write(true, UTF_8.encode("one"), Callback.NOOP);

            assertTrue(latch.await(5, TimeUnit.SECONDS));

            Content.Chunk chunk = async.read();
            assertNotNull(chunk);
            chunk.release();
        }
    }

    @Test
    public void testChunkReleaseSucceedsWriteCallback()
    {
        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, 4096, 4096);

            AtomicInteger successCounter = new AtomicInteger();
            AtomicReference<Throwable> failureRef = new AtomicReference<>();

            buffered.write(true, ByteBuffer.wrap(new byte[1]), Callback.from(successCounter::incrementAndGet, failureRef::set));

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
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, 4096, 4096);

            AtomicInteger successCounter = new AtomicInteger();
            AtomicReference<Throwable> failureRef = new AtomicReference<>();

            buffered.write(true, ByteBuffer.wrap(new byte[0]), Callback.from(successCounter::incrementAndGet, failureRef::set));

            Content.Chunk chunk = async.read();
            assertThat(successCounter.get(), is(1));
            assertThat(chunk.isLast(), is(true));
            assertThat(chunk.hasRemaining(), is(false));
            assertThat(chunk.release(), is(true));
            assertThat(successCounter.get(), is(1));
            assertThat(failureRef.get(), is(nullValue()));
        }
    }

    @Test
    public void testWriteAfterWriteLast()
    {
        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, 4096, 4096);

            AtomicInteger successCounter = new AtomicInteger();
            AtomicReference<Throwable> failureRef = new AtomicReference<>();

            buffered.write(true, ByteBuffer.wrap(new byte[0]), Callback.from(successCounter::incrementAndGet, failureRef::set));

            Content.Chunk chunk = async.read();
            assertThat(chunk.isLast(), is(true));
            assertThat(chunk.release(), is(true));

            assertThat(successCounter.get(), is(1));
            assertThat(failureRef.get(), is(nullValue()));

            buffered.write(false, ByteBuffer.wrap(new byte[0]), Callback.from(successCounter::incrementAndGet, failureRef::set));
            assertThat(successCounter.get(), is(1));
            assertThat(failureRef.get(), instanceOf(IOException.class));
        }
    }

    @Test
    public void testLargeBuffer()
    {
        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, 4096, 4096);

            buffered.write(false, ByteBuffer.wrap("one ".getBytes(UTF_8)), Callback.NOOP);
            Content.Chunk chunk = async.read();
            assertThat(chunk, nullValue());

            buffered.write(false, ByteBuffer.wrap("two".getBytes(UTF_8)), Callback.NOOP);
            chunk = async.read();
            assertThat(chunk, nullValue());

            buffered.write(true, null, Callback.NOOP);
            chunk = async.read();
            assertThat(chunk.isLast(), is(true));
            assertThat(BufferUtil.toString(chunk.getByteBuffer(), UTF_8), is("one two"));
            assertThat(chunk.release(), is(true));
        }
    }

    @Test
    public void testSmallBuffer()
    {
        int maxBufferSize = 16;
        byte[] input1 = new byte[1024];
        Arrays.fill(input1, (byte)'1');
        byte[] input2 = new byte[1023];
        Arrays.fill(input2, (byte)'2');

        ByteBuffer accumulatingBuffer = BufferUtil.allocate(4096);
        BufferUtil.flipToFill(accumulatingBuffer);

        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, maxBufferSize, maxBufferSize);

            buffered.write(false, ByteBuffer.wrap(input1), Callback.from(() ->
                buffered.write(true, ByteBuffer.wrap(input2), Callback.NOOP)));

            int loopCount = 0;
            while (true)
            {
                loopCount++;
                Content.Chunk chunk = async.read();
                assertThat(chunk, notNullValue());
                accumulatingBuffer.put(chunk.getByteBuffer());
                assertThat(chunk.release(), is(true));
                if (chunk.isLast())
                    break;
            }
            assertThat(loopCount, is(2));

            BufferUtil.flipToFlush(accumulatingBuffer, 0);
            assertThat(accumulatingBuffer.remaining(), is(input1.length + input2.length));
            for (byte b : input1)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
            for (byte b : input2)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
        }
    }

    @Test
    public void testMaxAggregationSizeExceeded()
    {
        int maxBufferSize = 1024;
        int maxAggregationSize = 128;
        byte[] input1 = new byte[512];
        Arrays.fill(input1, (byte)'1');
        byte[] input2 = new byte[128];
        Arrays.fill(input2, (byte)'2');

        ByteBuffer accumulatingBuffer = BufferUtil.allocate(4096);
        BufferUtil.flipToFill(accumulatingBuffer);

        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, maxBufferSize, maxAggregationSize);

            buffered.write(false, ByteBuffer.wrap(input1), Callback.from(() ->
                buffered.write(true, ByteBuffer.wrap(input2), Callback.NOOP)));

            Content.Chunk chunk = async.read();
            assertThat(chunk, notNullValue());
            assertThat(chunk.remaining(), is(512));
            accumulatingBuffer.put(chunk.getByteBuffer());
            assertThat(chunk.release(), is(true));
            assertThat(chunk.isLast(), is(false));

            chunk = async.read();
            assertThat(chunk, notNullValue());
            assertThat(chunk.remaining(), is(128));
            accumulatingBuffer.put(chunk.getByteBuffer());
            assertThat(chunk.release(), is(true));
            assertThat(chunk.isLast(), is(true));

            BufferUtil.flipToFlush(accumulatingBuffer, 0);
            assertThat(accumulatingBuffer.remaining(), is(input1.length + input2.length));
            for (byte b : input1)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
            for (byte b : input2)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
        }
    }

    @Test
    public void testMaxAggregationSizeExceededAfterBuffering()
    {
        int maxBufferSize = 1024;
        int maxAggregationSize = 128;
        byte[] input1 = new byte[128];
        Arrays.fill(input1, (byte)'1');
        byte[] input2 = new byte[512];
        Arrays.fill(input2, (byte)'2');

        ByteBuffer accumulatingBuffer = BufferUtil.allocate(4096);
        BufferUtil.flipToFill(accumulatingBuffer);

        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, maxBufferSize, maxAggregationSize);

            buffered.write(false, ByteBuffer.wrap(input1), Callback.from(() ->
                buffered.write(true, ByteBuffer.wrap(input2), Callback.NOOP)));

            Content.Chunk chunk = async.read();
            assertThat(chunk, notNullValue());
            assertThat(chunk.remaining(), is(128));
            accumulatingBuffer.put(chunk.getByteBuffer());
            assertThat(chunk.release(), is(true));
            assertThat(chunk.isLast(), is(false));

            chunk = async.read();
            assertThat(chunk, notNullValue());
            assertThat(chunk.remaining(), is(512));
            accumulatingBuffer.put(chunk.getByteBuffer());
            assertThat(chunk.release(), is(true));
            assertThat(chunk.isLast(), is(true));

            BufferUtil.flipToFlush(accumulatingBuffer, 0);
            assertThat(accumulatingBuffer.remaining(), is(input1.length + input2.length));
            for (byte b : input1)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
            for (byte b : input2)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
        }
    }

    @Test
    public void testMaxAggregationSizeRespected()
    {
        int maxBufferSize = 1024;
        int maxAggregationSize = 128;
        byte[] input1 = new byte[128];
        Arrays.fill(input1, (byte)'1');
        byte[] input2 = new byte[128];
        Arrays.fill(input2, (byte)'2');

        ByteBuffer accumulatingBuffer = BufferUtil.allocate(4096);
        BufferUtil.flipToFill(accumulatingBuffer);

        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, maxBufferSize, maxAggregationSize);

            buffered.write(false, ByteBuffer.wrap(input1), Callback.from(() ->
                buffered.write(true, ByteBuffer.wrap(input2), Callback.NOOP)));

            Content.Chunk chunk = async.read();
            assertThat(chunk, notNullValue());
            assertThat(chunk.remaining(), is(256));
            accumulatingBuffer.put(chunk.getByteBuffer());
            assertThat(chunk.release(), is(true));
            assertThat(chunk.isLast(), is(true));

            BufferUtil.flipToFlush(accumulatingBuffer, 0);
            assertThat(accumulatingBuffer.remaining(), is(input1.length + input2.length));
            for (byte b : input1)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
            for (byte b : input2)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
        }
    }

    @Test
    public void testBufferGrowth()
    {
        byte[] input1 = new byte[4000];
        Arrays.fill(input1, (byte)'1');
        byte[] input2 = new byte[4000];
        Arrays.fill(input2, (byte)'2');
        byte[] input3 = new byte[2000];
        Arrays.fill(input3, (byte)'3');

        ByteBuffer accumulatingBuffer = BufferUtil.allocate(16384);
        BufferUtil.flipToFill(accumulatingBuffer);

        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, 4096, 4096);

            buffered.write(false, ByteBuffer.wrap(input1), Callback.from(() ->
                buffered.write(false, ByteBuffer.wrap(input2), Callback.from(() ->
                    buffered.write(true, ByteBuffer.wrap(input3), Callback.NOOP)))));

            // We expect 3 buffer flushes: 4096b + 4096b + 1808b == 10_000b.
            Content.Chunk chunk = async.read();
            assertThat(chunk, notNullValue());
            assertThat(chunk.remaining(), is(4096));
            accumulatingBuffer.put(chunk.getByteBuffer());
            assertThat(chunk.release(), is(true));
            assertThat(chunk.isLast(), is(false));

            chunk = async.read();
            assertThat(chunk, notNullValue());
            assertThat(chunk.remaining(), is(4096));
            accumulatingBuffer.put(chunk.getByteBuffer());
            assertThat(chunk.release(), is(true));
            assertThat(chunk.isLast(), is(false));

            chunk = async.read();
            assertThat(chunk, notNullValue());
            assertThat(chunk.remaining(), is(1808));
            accumulatingBuffer.put(chunk.getByteBuffer());
            assertThat(chunk.release(), is(true));
            assertThat(chunk.isLast(), is(true));

            BufferUtil.flipToFlush(accumulatingBuffer, 0);
            assertThat(accumulatingBuffer.remaining(), is(input1.length + input2.length + input3.length));
            for (byte b : input1)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
            for (byte b : input2)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
            for (byte b : input3)
            {
                assertThat(accumulatingBuffer.get(), is(b));
            }
        }
    }

    @Test
    public void testByteByByteRecursion() throws Exception
    {
        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, 4096, 4096);
            AtomicInteger count = new AtomicInteger(8192);
            CountDownLatch complete = new CountDownLatch(1);
            Callback callback = new Callback()
            {
                @Override
                public void succeeded()
                {
                    int c = count.decrementAndGet();
                    ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[]{(byte)c});
                    if (c >= 0)
                        buffered.write(c == 0, byteBuffer, this);
                    else
                        complete.countDown();
                }
            };

            callback.succeeded();

            Content.Chunk read = async.read();
            assertThat(read.isLast(), is(false));
            assertThat(read.remaining(), is(4096));
            assertThat(read.release(), is(true));

            read = async.read();
            assertThat(read.isLast(), is(true));
            assertThat(read.remaining(), is(4096));
            assertThat(read.release(), is(true));

            assertTrue(complete.await(5, TimeUnit.SECONDS));
            assertThat(count.get(), is(-1));
        }
    }

    @Test
    public void testByteByByteAsync() throws Exception
    {
        try (AsyncContent async = new AsyncContent())
        {
            BufferedContentSink buffered = new BufferedContentSink(async, _bufferPool, true, 1024, 1024);
            AtomicInteger count = new AtomicInteger(2048);
            CountDownLatch complete = new CountDownLatch(1);
            Callback callback = new Callback()
            {
                @Override
                public void succeeded()
                {
                    int c = count.decrementAndGet();
                    if (c >= 0)
                    {
                        Callback cb = this;
                        new Thread(() ->
                        {
                            ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[]{(byte)c});
                            buffered.write(c == 0, byteBuffer, cb);
                        }).start();
                    }
                    else
                    {
                        complete.countDown();
                    }
                }
            };

            callback.succeeded();

            Content.Chunk read = await().atMost(5, TimeUnit.SECONDS).until(async::read, Objects::nonNull);
            assertThat(read.isLast(), is(false));
            assertThat(read.remaining(), is(1024));
            assertThat(read.release(), is(true));

            read = await().atMost(5, TimeUnit.SECONDS).until(async::read, Objects::nonNull);
            assertThat(read.isLast(), is(true));
            assertThat(read.remaining(), is(1024));
            assertThat(read.release(), is(true));

            assertTrue(complete.await(5, TimeUnit.SECONDS));
            assertThat(count.get(), is(-1));
        }
    }
}
