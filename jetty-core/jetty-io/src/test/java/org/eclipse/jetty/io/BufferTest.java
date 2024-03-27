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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BufferTest
{
    public static final int MIN_CAPACITY = 32;
    public static final int MAX_CAPACITY = 64;
    private static ArrayByteBufferPool.Tracking _pool;

    @BeforeAll
    public static void beforeAll()
    {
        _pool = new ArrayByteBufferPool.Tracking(MIN_CAPACITY, MIN_CAPACITY, MAX_CAPACITY, Integer.MAX_VALUE);
    }

    @AfterAll
    public static void afterAll()
    {
        assertThat("Leaks: " + _pool.dumpLeaks(), _pool.getLeaks().size(), is(0));
    }

    public static Stream<Arguments> buffers()
    {
        return Stream.of(
            Arguments.of(_pool.acquire(MIN_CAPACITY, true)),
            Arguments.of(_pool.acquire(MIN_CAPACITY, false)),
            Arguments.of(new RetainableByteBuffer.Mutable.Aggregator(_pool, true, MIN_CAPACITY, MIN_CAPACITY)),
            Arguments.of(new RetainableByteBuffer.Mutable.Aggregator(_pool, false, MIN_CAPACITY, MIN_CAPACITY)),
            Arguments.of(new RetainableByteBuffer.Mutable.Accumulator(_pool, true, MIN_CAPACITY)),
            Arguments.of(new RetainableByteBuffer.Mutable.Accumulator(_pool, false, MIN_CAPACITY))
        );
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testEmptyBuffer(RetainableByteBuffer buffer)
    {
        assertThat(buffer.remaining(), is(0));
        assertFalse(buffer.hasRemaining());
        if (buffer instanceof RetainableByteBuffer.Mutable mutable)
        {
            assertThat(mutable.capacity(), greaterThanOrEqualTo(MIN_CAPACITY));
            assertFalse(mutable.isFull());
        }

        assertThat(buffer.getByteBuffer().remaining(), is(0));
        assertFalse(buffer.getByteBuffer().hasRemaining());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendOneByte(RetainableByteBuffer.Mutable buffer)
    {
        byte[] bytes = new byte[] {'-', 'X', '-'};
        while (!buffer.isFull())
            assertThat(buffer.append(ByteBuffer.wrap(bytes, 1, 1)), is(true));

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendOneByteRetainable(RetainableByteBuffer.Mutable buffer)
    {
        RetainableByteBuffer toAppend = _pool.acquire(1, true);
        BufferUtil.append(toAppend.getByteBuffer(), (byte)'X');
        assertThat(buffer.append(toAppend), is(true));
        assertFalse(toAppend.hasRemaining());
        toAppend.release();
        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X"));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendMoreBytesThanCapacity(RetainableByteBuffer.Mutable buffer)
    {
        byte[] bytes = new byte[MAX_CAPACITY * 2];
        Arrays.fill(bytes, (byte)'X');
        ByteBuffer b = ByteBuffer.wrap(bytes);

        if (buffer.append(b))
        {
            assertTrue(BufferUtil.isEmpty(b));
            assertThat(buffer.capacity(), greaterThanOrEqualTo(MAX_CAPACITY * 2));
        }
        else
        {
            assertFalse(BufferUtil.isEmpty(b));
            assertThat(b.remaining(), is(MAX_CAPACITY * 2 - buffer.capacity()));
        }

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        assertTrue(buffer.isFull());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendMoreBytesThanCapacityRetainable(RetainableByteBuffer.Mutable buffer)
    {
        RetainableByteBuffer toAppend = _pool.acquire(MAX_CAPACITY * 2, true);
        int pos = BufferUtil.flipToFill(toAppend.getByteBuffer());
        byte[] bytes = new byte[MAX_CAPACITY * 2];
        Arrays.fill(bytes, (byte)'X');
        toAppend.getByteBuffer().put(bytes);
        BufferUtil.flipToFlush(toAppend.getByteBuffer(), pos);

        if (buffer.append(toAppend))
        {
            assertTrue(BufferUtil.isEmpty(toAppend.getByteBuffer()));
            assertThat(buffer.capacity(), greaterThanOrEqualTo(MAX_CAPACITY * 2));
        }
        else
        {
            assertFalse(BufferUtil.isEmpty(toAppend.getByteBuffer()));
            assertThat(toAppend.remaining(), is(MAX_CAPACITY * 2 - buffer.capacity()));
        }
        toAppend.release();

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        assertTrue(buffer.isFull());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendSmallByteBuffer(RetainableByteBuffer.Mutable buffer)
    {
        byte[] bytes = new byte[] {'-', 'X', '-'};
        ByteBuffer from = ByteBuffer.wrap(bytes, 1, 1);
        while (!buffer.isFull())
        {
            ByteBuffer slice = from.slice();
            buffer.append(slice);
            assertFalse(slice.hasRemaining());
        }

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendBigByteBuffer(RetainableByteBuffer.Mutable buffer)
    {
        ByteBuffer from = BufferUtil.toBuffer("X".repeat(buffer.capacity() * 2));
        buffer.append(from);
        assertTrue(from.hasRemaining());
        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        assertTrue(buffer.isFull());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testNonRetainableWriteTo(RetainableByteBuffer.Mutable buffer) throws Exception
    {
        buffer.append(RetainableByteBuffer.wrap(BufferUtil.toBuffer("Hello")));
        buffer.append(RetainableByteBuffer.wrap(BufferUtil.toBuffer(" ")));
        buffer.append(RetainableByteBuffer.wrap(BufferUtil.toBuffer("World!")));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FutureCallback callback = new FutureCallback();
        buffer.writeTo(Content.Sink.from(out), true, callback);
        callback.get(5, TimeUnit.SECONDS);
        assertThat(out.toString(StandardCharsets.ISO_8859_1), is("Hello World!"));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testRetainableWriteTo(RetainableByteBuffer.Mutable buffer) throws Exception
    {
        CountDownLatch released = new CountDownLatch(3);
        RetainableByteBuffer[] buffers = new RetainableByteBuffer[3];
        buffer.append(buffers[0] = RetainableByteBuffer.wrap(BufferUtil.toBuffer("Hello"), released::countDown));
        buffer.append(buffers[1] = RetainableByteBuffer.wrap(BufferUtil.toBuffer(" "), released::countDown));
        buffer.append(buffers[2] = RetainableByteBuffer.wrap(BufferUtil.toBuffer("World!"), released::countDown));
        Arrays.asList(buffers).forEach(RetainableByteBuffer::release);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FutureCallback callback = new FutureCallback();
        buffer.writeTo(Content.Sink.from(out), true, callback);
        callback.get(5, TimeUnit.SECONDS);
        assertThat(out.toString(StandardCharsets.ISO_8859_1), is("Hello World!"));

        buffer.release();
        assertTrue(released.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testCopy(RetainableByteBuffer.Mutable original)
    {
        original.append(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
        RetainableByteBuffer.Mutable copy = original.copy();

        assertEquals(0, copy.space());
        assertEquals(5, copy.remaining());
        assertEquals(5, original.remaining());
        assertEquals("hello", StandardCharsets.UTF_8.decode(original.getByteBuffer()).toString());
        assertEquals("hello", StandardCharsets.UTF_8.decode(copy.getByteBuffer()).toString());

        copy.release();
        original.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testCopyThenModifyOriginal(RetainableByteBuffer.Mutable original)
    {
        original.append(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
        RetainableByteBuffer.Mutable copy = original.copy();
        original.append(ByteBuffer.wrap(" world".getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, copy.space());
        assertEquals(5, copy.remaining());
        assertEquals(11, original.remaining());
        assertEquals("hello world", StandardCharsets.UTF_8.decode(original.getByteBuffer()).toString());
        assertEquals("hello", StandardCharsets.UTF_8.decode(copy.getByteBuffer()).toString());

        copy.release();
        original.release();
    }
}
