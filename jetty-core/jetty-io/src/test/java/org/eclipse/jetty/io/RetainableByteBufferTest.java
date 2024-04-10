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
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetainableByteBufferTest
{
    public static final int MIN_CAPACITY = 32;
    public static final int MAX_CAPACITY = 64;
    public static final String TEST_TEXT = "xxxTesting 123";
    public static final byte[] TEST_TEXT_BYTES = TEST_TEXT.getBytes(StandardCharsets.UTF_8);
    public static final int TEST_OFFSET = 3;
    public static final int TEST_LENGTH = TEST_TEXT_BYTES.length - TEST_OFFSET;
    public static final String TEST_EXPECTED = "Testing 123";
    public static final byte[] TEST_EXPECTED_BYTES = "Testing 123".getBytes(StandardCharsets.UTF_8);

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
        List<Supplier<RetainableByteBuffer>> list = new ArrayList<>();

        list.add(() -> RetainableByteBuffer.wrap(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH)));
        list.add(() -> RetainableByteBuffer.wrap(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).slice()));
        list.add(() -> RetainableByteBuffer.wrap(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).asReadOnlyBuffer()));
        list.add(() -> RetainableByteBuffer.wrap(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).duplicate()));

        list.add(() ->
        {
            RetainableByteBuffer rbb = _pool.acquire(1024, false);
            ByteBuffer byteBuffer = rbb.getByteBuffer();
            BufferUtil.append(byteBuffer, TEST_TEXT_BYTES);
            byteBuffer.position(byteBuffer.position() + TEST_OFFSET);
            return rbb;
        });

        list.add(() ->
        {
            RetainableByteBuffer rbb = _pool.acquire(1024, true);
            ByteBuffer byteBuffer = rbb.getByteBuffer();
            BufferUtil.append(byteBuffer, TEST_TEXT_BYTES);
            byteBuffer.position(byteBuffer.position() + TEST_OFFSET);
            return rbb;
        });

        return list.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testNotEmptyBuffer(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.hasRemaining());
        assertThat(buffer.remaining(), is(TEST_EXPECTED_BYTES.length));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testGetByteBuffer(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        assertThat(BufferUtil.toString(byteBuffer), equalTo(TEST_EXPECTED));
        assertThat(byteBuffer.get(), equalTo((byte)TEST_EXPECTED.charAt(0)));
        assertThat(buffer.remaining(), equalTo(byteBuffer.remaining()));
        BufferUtil.clear(byteBuffer);
        assertTrue(buffer.isEmpty());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testGet(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        Utf8StringBuilder builder = new Utf8StringBuilder();
        for (int i = buffer.remaining(); i-- > 0;)
            builder.append(buffer.get());
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.remaining(), is(0));
        assertThat(builder.toCompleteString(), is(TEST_EXPECTED));
        assertThrows(BufferUnderflowException.class, buffer::get);
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testGetBytes(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        byte[] testing = new byte[1024];
        assertThat(buffer.get(testing, 0, 8), equalTo(8));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, 8)), equalTo("Testing "));

        assertThat(buffer.get(testing, 8, 1024), equalTo(TEST_EXPECTED_BYTES.length - 8));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length)), equalTo(TEST_EXPECTED));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testCopy(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        RetainableByteBuffer copy = buffer.copy();

        byte[] testing = new byte[1024];
        assertThat(copy.get(testing, 0, 1024), equalTo(TEST_EXPECTED_BYTES.length));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length)), equalTo(TEST_EXPECTED));
        copy.release();

        testing = new byte[1024];
        assertThat(buffer.get(testing, 0, 1024), equalTo(TEST_EXPECTED_BYTES.length));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length)), equalTo(TEST_EXPECTED));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testSkipLength(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        buffer.skip(buffer.remaining());
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.remaining(), is(0));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testSkip1by1(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        for (int i = buffer.remaining(); i-- > 0;)
        {
            buffer.skip(1);
            assertThat(buffer.remaining(), is(i));
        }
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.remaining(), is(0));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testSliceOnly(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        buffer.slice().release();
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testSlice(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        RetainableByteBuffer slice = buffer.slice();

        byte[] testing = new byte[1024];
        assertThat(slice.get(testing, 0, 1024), equalTo(TEST_EXPECTED_BYTES.length));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length)), equalTo(TEST_EXPECTED));
        slice.release();

        testing = new byte[1024];
        assertThat(buffer.get(testing, 0, 1024), equalTo(TEST_EXPECTED_BYTES.length));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length)), equalTo(TEST_EXPECTED));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testSliceAndSkipNLength(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        for (int i = buffer.remaining(); i > 0; i--)
        {
            RetainableByteBuffer slice = buffer.slice();
            assertThat(slice.skip(i), equalTo((long)i));
            assertThat(BufferUtil.toString(slice.getByteBuffer()), equalTo(TEST_EXPECTED.substring(i)));
            slice.release();
        }
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendToByteBuffer(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        ByteBuffer byteBuffer = BufferUtil.allocate(1024);
        BufferUtil.append(byteBuffer, "<<<");
        assertTrue(buffer.appendTo(byteBuffer));
        assertTrue(buffer.isEmpty());
        BufferUtil.append(byteBuffer, ">>>");
        assertThat(BufferUtil.toString(byteBuffer), equalTo("<<<" + TEST_EXPECTED + ">>>"));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendToByteBufferLimited(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        ByteBuffer byteBuffer = BufferUtil.allocate(8);
        assertFalse(buffer.appendTo(byteBuffer));
        assertFalse(buffer.isEmpty());
        assertThat(BufferUtil.toString(byteBuffer), equalTo(TEST_EXPECTED.substring(0, 8)));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendToRetainableByteBuffer(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        RetainableByteBuffer rbb = RetainableByteBuffer.wrap(BufferUtil.allocate(1024));
        assertTrue(buffer.appendTo(rbb));
        assertTrue(buffer.isEmpty());
        assertThat(BufferUtil.toString(rbb.getByteBuffer()), equalTo(TEST_EXPECTED));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testAppendToRetainableByteBufferLimited(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        RetainableByteBuffer rbb = RetainableByteBuffer.wrap(BufferUtil.allocate(8));
        assertFalse(buffer.appendTo(rbb));
        assertFalse(buffer.isEmpty());
        assertThat(BufferUtil.toString(rbb.getByteBuffer()), equalTo(TEST_EXPECTED.substring(0, 8)));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testPutTo(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        ByteBuffer byteBuffer = BufferUtil.allocate(1024);
        int p = BufferUtil.flipToFill(byteBuffer);
        byteBuffer.put("<<<".getBytes(StandardCharsets.UTF_8));
        buffer.putTo(byteBuffer);
        assertTrue(buffer.isEmpty());
        byteBuffer.put(">>>".getBytes(StandardCharsets.UTF_8));
        BufferUtil.flipToFlush(byteBuffer, p);
        assertThat(BufferUtil.toString(byteBuffer), equalTo("<<<" + TEST_EXPECTED + ">>>"));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testPutToLimited(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        ByteBuffer byteBuffer = BufferUtil.allocate(11);
        BufferUtil.flipToFill(byteBuffer);
        byteBuffer.put("<<<".getBytes(StandardCharsets.UTF_8));
        assertThrows(BufferOverflowException.class, () -> buffer.putTo(byteBuffer));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testWriteTo(Supplier<RetainableByteBuffer> supplier) throws Exception
    {
        RetainableByteBuffer buffer = supplier.get();

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Content.Sink sink = Content.Sink.from(bout);
        Callback.Completable callback = new Callback.Completable();
        buffer.writeTo(sink, true, callback);
        callback.get(5, TimeUnit.SECONDS);
        assertThat(bout.toString(StandardCharsets.UTF_8), is(TEST_EXPECTED));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testToDetailString(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        String detailString = buffer.toDetailString();
        assertThat(detailString, containsString(buffer.getClass().getSimpleName()));
        assertThat(detailString, containsString("<<<" + TEST_EXPECTED + ">>>"));
        buffer.release();
    }

    public static Stream<Arguments> mutables()
    {
        return Stream.of(
            Arguments.of(new RetainableByteBuffer.Growable(_pool, true, MAX_CAPACITY)),
            Arguments.of(new RetainableByteBuffer.Growable(_pool, false, MAX_CAPACITY)),
            Arguments.of(new RetainableByteBuffer.Growable(_pool, true, MAX_CAPACITY, 0)),
            Arguments.of(new RetainableByteBuffer.Growable(_pool, false, MAX_CAPACITY, 0)),
            Arguments.of(new RetainableByteBuffer.Growable(_pool, true, MAX_CAPACITY, 0, 0)),
            Arguments.of(new RetainableByteBuffer.Growable(_pool, false, MAX_CAPACITY, 0, 0)),
            Arguments.of(new RetainableByteBuffer.Growable(_pool, true, MAX_CAPACITY, 32, 0)),
            Arguments.of(new RetainableByteBuffer.Growable(_pool, false, MAX_CAPACITY, 32, 0))
        );
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testEmptyMutableBuffer(RetainableByteBuffer.Appendable buffer)
    {
        assertThat(buffer.remaining(), is(0));
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.capacity(), greaterThanOrEqualTo(MIN_CAPACITY));
        assertFalse(buffer.isFull());

        assertThat(buffer.remaining(), is(0));
        assertFalse(buffer.getByteBuffer().hasRemaining());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAppendOneByte(RetainableByteBuffer.Appendable buffer)
    {
        byte[] bytes = new byte[] {'-', 'X', '-'};
        while (!buffer.isFull())
            assertThat(buffer.append(ByteBuffer.wrap(bytes, 1, 1)), is(true));

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAppendOneByteRetainable(RetainableByteBuffer.Appendable buffer)
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
    @MethodSource("mutables")
    public void testAppendMoreBytesThanCapacity(RetainableByteBuffer.Appendable buffer)
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
    @MethodSource("mutables")
    public void testAppendMoreBytesThanCapacityRetainable(RetainableByteBuffer.Appendable buffer)
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
    @MethodSource("mutables")
    public void testAppendSmallByteBuffer(RetainableByteBuffer.Appendable buffer)
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
    @MethodSource("mutables")
    public void testAppendBigByteBuffer(RetainableByteBuffer.Appendable buffer)
    {
        ByteBuffer from = BufferUtil.toBuffer("X".repeat(MAX_CAPACITY * 2));
        assertFalse(buffer.append(from));
        assertTrue(from.hasRemaining());
        assertThat(from.remaining(), equalTo(MAX_CAPACITY));
        assertThat(buffer.remaining(), equalTo(MAX_CAPACITY));
        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        assertTrue(buffer.isFull());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testNonRetainableWriteTo(RetainableByteBuffer.Appendable buffer) throws Exception
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
    @MethodSource("mutables")
    public void testRetainableWriteTo(RetainableByteBuffer.Appendable buffer) throws Exception
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
    @MethodSource("mutables")
    public void testCopyMutable(RetainableByteBuffer.Appendable original)
    {
        ByteBuffer bytes = ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8));
        original.append(bytes);
        RetainableByteBuffer copy = original.copy();

        assertEquals(0, BufferUtil.space(copy.getByteBuffer()));
        assertEquals(5, copy.remaining());
        assertEquals(5, original.remaining());
        assertEquals("hello", StandardCharsets.UTF_8.decode(original.getByteBuffer()).toString());
        assertEquals("hello", StandardCharsets.UTF_8.decode(copy.getByteBuffer()).toString());

        copy.release();
        original.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testCopyMutableThenModifyOriginal(RetainableByteBuffer.Appendable original)
    {
        original.append(ByteBuffer.wrap("hello".getBytes(StandardCharsets.UTF_8)));
        RetainableByteBuffer copy = original.copy();
        original.append(ByteBuffer.wrap(" world".getBytes(StandardCharsets.UTF_8)));

        assertEquals(0, BufferUtil.space(copy.getByteBuffer()));
        assertEquals(5, copy.remaining());
        assertEquals(11, original.remaining());
        assertEquals("hello world", StandardCharsets.UTF_8.decode(original.getByteBuffer()).toString());
        assertEquals("hello", StandardCharsets.UTF_8.decode(copy.getByteBuffer()).toString());

        copy.release();
        original.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testToLargeDetailString(RetainableByteBuffer.Appendable buffer)
    {
        assertTrue(buffer.append(BufferUtil.toBuffer("0123456789ABCDEF")));
        assertTrue(buffer.append(BufferUtil.toBuffer("xxxxxxxxxxxxxxxx")));
        assertTrue(buffer.append(BufferUtil.toBuffer("xxxxxxxxxxxxxxxx")));
        assertTrue(buffer.append(BufferUtil.toBuffer("abcdefghijklmnop")));
        assertThat(buffer.toDetailString(), containsString("<<<0123456789ABCDEF...abcdefghijklmnop>>>"));
        buffer.release();
    }
}
