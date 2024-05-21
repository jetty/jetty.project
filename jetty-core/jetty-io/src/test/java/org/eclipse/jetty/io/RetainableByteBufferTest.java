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
import java.net.SocketAddress;
import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.jetty.io.RetainableByteBuffer.Mutable;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
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
        List<Supplier<RetainableByteBuffer>> list = new ArrayList<>()
        {
            @Override
            public boolean add(Supplier<RetainableByteBuffer> parent)
            {
                RetainableByteBuffer buffer = parent.get();
                String name = buffer.toDetailString().replaceAll("@[0-9a-zA-Z]*", "");
                buffer.release();
                return super.add(new Supplier<>()
                {
                    @Override
                    public RetainableByteBuffer get()
                    {
                        return parent.get();
                    }

                    @Override
                    public String toString()
                    {
                        return name;
                    }
                });
            }
        };

        list.add(() -> RetainableByteBuffer.wrap(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH)));
        list.add(() -> RetainableByteBuffer.wrap(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).slice()));
        list.add(() -> RetainableByteBuffer.wrap(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).asReadOnlyBuffer()));
        list.add(() -> RetainableByteBuffer.wrap(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).duplicate()));

        list.add(() -> new RetainableByteBuffer.FixedCapacity(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH)));
        list.add(() -> new RetainableByteBuffer.FixedCapacity(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).slice()));
        list.add(() -> new RetainableByteBuffer.FixedCapacity(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).asReadOnlyBuffer()));
        list.add(() -> new RetainableByteBuffer.FixedCapacity(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH).duplicate()));

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


        // Test each of the mutables in various states
        int mutables = 0;
        while (true)
        {
            Mutable m = mutable(mutables);
            if (m == null)
                break;
            mutables++;
            m.release();
        }

        for (int i = 0; i < mutables; i++)
        {
            final int index = i;

            list.add(() ->
            {
                Mutable mutable = Objects.requireNonNull(mutable(index));
                mutable.put(TEST_EXPECTED_BYTES);
                return mutable;
            });

            list.add(() ->
            {
                Mutable mutable = Objects.requireNonNull(mutable(index));
                mutable.add(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, TEST_LENGTH));
                return mutable;
            });

            list.add(() ->
            {
                Mutable mutable = Objects.requireNonNull(mutable(index));
                int half = TEST_LENGTH / 2;
                RetainableByteBuffer first = _pool.acquire(half, mutable.isDirect());
                first.asMutable().append(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET, half));
                RetainableByteBuffer second = _pool.acquire(TEST_LENGTH - half, mutable.isDirect());
                second.asMutable().append(BufferUtil.toBuffer(TEST_TEXT_BYTES, TEST_OFFSET + half, TEST_LENGTH - half));
                mutable.add(first).add(second);
                return mutable;
            });

            list.add(() ->
            {
                Mutable mutable = Objects.requireNonNull(mutable(index));
                mutable.append(BufferUtil.toBuffer(TEST_TEXT_BYTES));
                mutable.skip(3);
                return mutable;
            });

            list.add(() ->
            {
                Mutable mutable = Objects.requireNonNull(mutable(index));
                for (byte b : TEST_TEXT_BYTES)
                    mutable.add(BufferUtil.toBuffer(new byte[]{b}));
                mutable.skip(TEST_OFFSET);
                return mutable;
            });
        }

        return list.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testNotEmptyBuffer(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        assertFalse(buffer.isEmpty());
        assertTrue(buffer.hasRemaining());
        assertThat(buffer.size(), is((long)TEST_EXPECTED_BYTES.length));
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
        for (int i = buffer.remaining(); i-- > 0; )
            builder.append(buffer.get());

        assertTrue(buffer.isEmpty());
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.size(), is(0L));
        assertThat(buffer.remaining(), is(0));
        assertThat(builder.toCompleteString(), is(TEST_EXPECTED));
        assertThrows(BufferUnderflowException.class, buffer::get);
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testGetAtIndex(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        Utf8StringBuilder builder = new Utf8StringBuilder();

        for (int i = 0; i < buffer.remaining(); i++)
            builder.append(buffer.get(i));

        assertFalse(buffer.isEmpty());
        assertTrue(buffer.hasRemaining());
        assertThat(buffer.size(), is((long)TEST_EXPECTED_BYTES.length));
        assertThat(buffer.remaining(), is(TEST_EXPECTED_BYTES.length));
        assertThat(builder.toCompleteString(), is(TEST_EXPECTED));

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
    public void testClear(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        buffer.clear();
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.size(), is(0L));
        assertThat(buffer.remaining(), is(0));
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
        assertThat(buffer.size(), is(0L));
        assertThat(buffer.remaining(), is(0));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testSkip1by1(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        for (int i = buffer.remaining(); i-- > 0; )
        {
            buffer.skip(1);
            assertThat(buffer.size(), is((long)i));
            assertThat(buffer.remaining(), is(i));
        }
        assertTrue(buffer.isEmpty());
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.size(), is(0L));
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
    public void testLimitLess(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        buffer.limit(buffer.size() - 2);

        byte[] testing = new byte[1024];
        assertThat(buffer.get(testing, 0, 1024), equalTo(TEST_EXPECTED_BYTES.length - 2));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length - 2)), equalTo(TEST_EXPECTED.substring(0, TEST_EXPECTED.length() - 2)));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testLimitMore(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        buffer.limit(buffer.size() + 2);

        byte[] testing = new byte[1024];
        assertThat(buffer.get(testing, 0, 1024), equalTo(TEST_EXPECTED_BYTES.length));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length)), equalTo(TEST_EXPECTED));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testSliceLess(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        RetainableByteBuffer slice = buffer.slice(buffer.size() - 2);

        byte[] testing = new byte[1024];
        assertThat(slice.get(testing, 0, 1024), equalTo(TEST_EXPECTED_BYTES.length - 2));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length - 2)), equalTo(TEST_EXPECTED.substring(0, TEST_EXPECTED.length() - 2)));
        slice.release();

        testing = new byte[1024];
        assertThat(buffer.get(testing, 0, 1024), equalTo(TEST_EXPECTED_BYTES.length));
        assertThat(BufferUtil.toString(BufferUtil.toBuffer(testing, 0, TEST_EXPECTED_BYTES.length)), equalTo(TEST_EXPECTED));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testSliceMore(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        RetainableByteBuffer slice = buffer.slice(buffer.size() + 2);

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
    @MethodSource("mutables")
    public void testSliceLength(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.put("Hello".getBytes(StandardCharsets.UTF_8));
        CountDownLatch released = new CountDownLatch(2);
        buffer.add(RetainableByteBuffer.wrap(BufferUtil.toBuffer(" cruel ".getBytes(StandardCharsets.UTF_8)), released::countDown));
        buffer.add(RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!".getBytes(StandardCharsets.UTF_8)), released::countDown));

        RetainableByteBuffer hello = buffer.slice(5);
        buffer.skip(5);
        assertThat(BufferUtil.toString(hello.getByteBuffer()), is("Hello"));

        RetainableByteBuffer space = buffer.slice(1);
        buffer.skip(1);
        assertThat(BufferUtil.toString(space.getByteBuffer()), is(" "));

        RetainableByteBuffer cruel = buffer.slice(5);
        buffer.skip(5);
        assertThat(BufferUtil.toString(cruel.getByteBuffer()), is("cruel"));

        RetainableByteBuffer world = buffer.slice(6);
        buffer.skip(6);
        assertThat(world.skip(1), is(1L));
        assertThat(BufferUtil.toString(world.getByteBuffer()), is("world"));

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("!"));

        hello.release();
        space.release();
        cruel.release();
        world.release();
        assertTrue(buffer.release());

        assertThat(released.getCount(), is(0L));
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
    public void testWriteToBlocking(Supplier<RetainableByteBuffer> supplier) throws Exception
    {
        RetainableByteBuffer buffer = supplier.get();

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        Content.Sink sink = Content.Sink.from(bout);
        buffer.writeTo(sink, true);
        assertThat(bout.toString(StandardCharsets.UTF_8), is(TEST_EXPECTED));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testWriteToEndPoint(Supplier<RetainableByteBuffer> supplier) throws Exception
    {
        RetainableByteBuffer buffer = supplier.get();

        StringBuilder out = new StringBuilder();
        try (EndPoint endPoint = new AbstractEndPoint(new TimerScheduler())
        {
            @Override
            public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
            {
                for (ByteBuffer buffer : buffers)
                {
                    out.append(BufferUtil.toString(buffer));
                    buffer.position(buffer.limit());
                }

                callback.succeeded();
            }

            @Override
            public SocketAddress getLocalSocketAddress()
            {
                return null;
            }

            @Override
            public SocketAddress getRemoteSocketAddress()
            {
                return null;
            }

            @Override
            protected void onIncompleteFlush()
            {

            }

            @Override
            protected void needsFillInterest()
            {

            }

            @Override
            public Object getTransport()
            {
                return null;
            }
        })
        {
            Callback.Completable callback = new Callback.Completable();
            buffer.writeTo(endPoint, false, callback);
            endPoint.write(true, BufferUtil.toBuffer(" OK!"), Callback.NOOP);
            callback.get(5, TimeUnit.SECONDS);
        }

        assertThat(out.toString(), is(TEST_EXPECTED + " OK!"));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testWriteToEndPointLast(Supplier<RetainableByteBuffer> supplier) throws Exception
    {
        RetainableByteBuffer buffer = supplier.get();
        StringBuilder out = new StringBuilder();

        try (EndPoint endPoint = new AbstractEndPoint(new TimerScheduler())
        {
            @Override
            public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
            {
                for (ByteBuffer buffer : buffers)
                {
                    out.append(BufferUtil.toString(buffer));
                    buffer.limit(buffer.position());
                }

                callback.succeeded();
            }

            @Override
            public SocketAddress getLocalSocketAddress()
            {
                return null;
            }

            @Override
            public SocketAddress getRemoteSocketAddress()
            {
                return null;
            }

            @Override
            protected void onIncompleteFlush()
            {

            }

            @Override
            protected void needsFillInterest()
            {

            }

            @Override
            public Object getTransport()
            {
                return null;
            }
        })
        {
            Callback.Completable callback = new Callback.Completable();
            buffer.writeTo(endPoint, true, callback);
            callback.get(5, TimeUnit.SECONDS);
            assertFalse(endPoint.isOpen());
        }

        assertThat(out.toString(), is(TEST_EXPECTED));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testToString(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        String string = buffer.toString();
        assertThat(string, containsString(buffer.getClass().getSimpleName()));
        assertThat(string, not(containsString("={")));
        assertThat(string, containsString("[11/"));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("buffers")
    public void testToDetailString(Supplier<RetainableByteBuffer> supplier)
    {
        RetainableByteBuffer buffer = supplier.get();
        String string = buffer.toDetailString();
        assertThat(string, containsString(buffer.getClass().getSimpleName()));
        assertThat(string, anyOf(
            containsString("<" + TEST_EXPECTED + ">>>"),
            allOf(containsString("<T>>>"), containsString("<e>>>"), containsString("<s>>>"), containsString("<t>>>")),
            allOf(containsString("<Testi>>>"), containsString("<ng 123>>>"))
        ));
        buffer.release();
    }

    public static Mutable mutable(int index)
    {
        return switch (index)
        {
            case  0 -> new RetainableByteBuffer.FixedCapacity(BufferUtil.allocate(MAX_CAPACITY));
            case  1 -> new RetainableByteBuffer.FixedCapacity(BufferUtil.allocateDirect(MAX_CAPACITY));
            case  2 -> new RetainableByteBuffer.FixedCapacity(BufferUtil.allocate(2 * MAX_CAPACITY).limit(MAX_CAPACITY + MAX_CAPACITY / 2).position(MAX_CAPACITY / 2).slice().limit(0));
            case  3 -> new RetainableByteBuffer.FixedCapacity(BufferUtil.allocateDirect(2 * MAX_CAPACITY).limit(MAX_CAPACITY + MAX_CAPACITY / 2).position(MAX_CAPACITY / 2).slice().limit(0));
            case  4 -> _pool.acquire(MAX_CAPACITY, true).asMutable();
            case  5 -> _pool.acquire(MAX_CAPACITY, false).asMutable();
            case  6 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY);
            case  7 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY);
            case  8 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 0, -1);
            case  9 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY, 0, -1);
            case 10 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 32, -1);
            case 11 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY, 32, -1);
            case 12 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 0, 0);
            case 13 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY, 0, 0);
            case 14 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 32, 0);
            case 15 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY, 32, 0);
            case 16 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 0, 2);
            case 17 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY, 0, 2);
            case 18 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 32, 2);
            case 19 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY, 32, 2);
            case 20 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 0, Integer.MAX_VALUE);
            case 21 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY, 0, Integer.MAX_VALUE);
            case 22 -> new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 32, Integer.MAX_VALUE);
            case 23 -> new Mutable.DynamicCapacity(_pool, false, MAX_CAPACITY, 32, Integer.MAX_VALUE);
            case 24 ->
            {
                Mutable withAggregatable = new Mutable.DynamicCapacity(_pool, true, MAX_CAPACITY, 0, 0);
                withAggregatable.add(_pool.acquire(MAX_CAPACITY, false));
                yield withAggregatable;
            }
            default -> null;
        };
    }

    public static Stream<Arguments> mutables()
    {
        List<Arguments> list = new ArrayList<>();
        int i = 0;
        while (true)
        {
            Mutable m = mutable(i);
            if (m == null)
                break;
            String name = m.toString().replaceAll("@[0-9a-fA-F]*", "");
            m.release();
            int index = i++;
            Supplier<Mutable> supplier = new Supplier<>()
            {
                @Override
                public Mutable get()
                {
                    return mutable(index);
                }

                @Override
                public String toString()
                {
                    return name;
                }
            };
            list.add(Arguments.of(supplier));
        }
        return list.stream();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testEmptyMutablesBuffer(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        assertThat(buffer.size(), is(0L));
        assertThat(buffer.remaining(), is(0));
        assertFalse(buffer.hasRemaining());
        assertThat(buffer.capacity(), greaterThanOrEqualTo(MIN_CAPACITY));
        assertFalse(buffer.isFull());

        assertThat(buffer.size(), is(0L));
        assertThat(buffer.remaining(), is(0));
        assertFalse(buffer.getByteBuffer().hasRemaining());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAppendOneByte(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        byte[] bytes = new byte[]{'-', 'X', '-'};
        while (!buffer.isFull())
        {
            assertThat(buffer.append(ByteBuffer.wrap(bytes, 1, 1)), is(true));
        }

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testSpace(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        assertThat(buffer.space(), equalTo(buffer.maxSize()));
        assertThat(buffer.space(), equalTo((long)buffer.capacity()));
        byte[] bytes = new byte[]{'-', 'X', '-'};
        assertThat(buffer.append(ByteBuffer.wrap(bytes, 1, 1)), is(true));
        assertThat(buffer.space(), equalTo(buffer.maxSize() - 1L));
        assertThat((int)buffer.space(), equalTo(buffer.capacity() - 1));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAppendRetainable(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        CountDownLatch release = new CountDownLatch(3);
        RetainableByteBuffer hello = RetainableByteBuffer.wrap(BufferUtil.toBuffer("Hello"), release::countDown);
        RetainableByteBuffer cruel = RetainableByteBuffer.wrap(BufferUtil.toBuffer(" cruel "), release::countDown);
        RetainableByteBuffer world = RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!"), release::countDown);
        RetainableByteBuffer.Mutable cruelWorld = new RetainableByteBuffer.DynamicCapacity(null, false, -1, -1, 0);
        cruelWorld.add(cruel);
        cruelWorld.add(world);

        assertTrue(buffer.append(hello));
        assertTrue(buffer.append(cruelWorld));

        assertThat(BufferUtil.toString(buffer.getByteBuffer(), StandardCharsets.UTF_8), is("Hello cruel world!"));

        cruelWorld.release();
        buffer.release();
        hello.release();
        assertThat(release.getCount(), is(0L));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAppendOneByteRetainable(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
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
    public void testAppendMoreBytesThanCapacity(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        byte[] bytes = new byte[MAX_CAPACITY * 2];
        Arrays.fill(bytes, (byte)'X');
        bytes[0] = '!';
        bytes[1] = '>';
        ByteBuffer b = ByteBuffer.wrap(bytes);
        b.get();

        if (buffer.append(b))
        {
            assertTrue(BufferUtil.isEmpty(b));
            assertThat(buffer.capacity(), greaterThanOrEqualTo(MAX_CAPACITY * 2));
        }
        else
        {
            assertFalse(BufferUtil.isEmpty(b));
            assertThat(b.remaining(), is(MAX_CAPACITY * 2 - buffer.capacity() - 1));
        }

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is(">" + "X".repeat(buffer.capacity() - 1)));
        assertTrue(buffer.isFull());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAppendMoreBytesThanCapacityRetainable(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
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
    public void testAppendSmallByteBuffer(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        byte[] bytes = new byte[]{'-', 'X', '-'};
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
    public void testAppendBigByteBuffer(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        ByteBuffer from = BufferUtil.toBuffer("X".repeat(MAX_CAPACITY * 2));
        from.put(0, (byte)'!');
        from.put(1, (byte)'>');
        from.get();

        assertFalse(buffer.append(from));
        assertTrue(from.hasRemaining());
        assertThat(from.remaining(), equalTo(MAX_CAPACITY - 1));
        assertThat(buffer.remaining(), equalTo(MAX_CAPACITY));
        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is(">" + "X".repeat(buffer.capacity() - 1)));
        assertTrue(buffer.isFull());
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAddRetainable(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        CountDownLatch release = new CountDownLatch(3);
        RetainableByteBuffer hello = RetainableByteBuffer.wrap(BufferUtil.toBuffer("Hello"), release::countDown);
        RetainableByteBuffer cruel = RetainableByteBuffer.wrap(BufferUtil.toBuffer(" cruel "), release::countDown);
        RetainableByteBuffer world = RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!"), release::countDown);
        RetainableByteBuffer.Mutable cruelWorld = new RetainableByteBuffer.DynamicCapacity(null, false, -1, -1, 0);
        cruelWorld.add(cruel).add(world);

        buffer.add(hello).add(cruelWorld);

        assertThat(BufferUtil.toString(buffer.getByteBuffer(), StandardCharsets.UTF_8), is("Hello cruel world!"));

        buffer.release();
        assertThat(release.getCount(), is(0L));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAddOneByteRetainable(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        RetainableByteBuffer toAdd = _pool.acquire(1, true);
        BufferUtil.append(toAdd.getByteBuffer(), (byte)'X');

        toAdd.retain();
        buffer.add(toAdd);
        if (toAdd.release())
            assertThat(toAdd.remaining(), is(0));
        else
            assertThat(toAdd.remaining(), is(1));
        
        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X"));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAddMoreBytesThanCapacity(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        byte[] bytes = new byte[MAX_CAPACITY * 2];
        Arrays.fill(bytes, (byte)'X');
        ByteBuffer b = ByteBuffer.wrap(bytes);
        assertThrows(BufferOverflowException.class, () -> buffer.add(b));
        assertThat(b.remaining(), is(MAX_CAPACITY * 2));
        assertThat(buffer.size(), is(0L));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAddMoreBytesThanCapacityRetainable(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        RetainableByteBuffer toAdd = _pool.acquire(MAX_CAPACITY * 2, true);
        int pos = BufferUtil.flipToFill(toAdd.getByteBuffer());
        byte[] bytes = new byte[MAX_CAPACITY * 2];
        Arrays.fill(bytes, (byte)'X');
        toAdd.getByteBuffer().put(bytes);
        BufferUtil.flipToFlush(toAdd.getByteBuffer(), pos);

        assertThrows(BufferOverflowException.class, () -> buffer.add(toAdd));
        assertThat(toAdd.remaining(), is(MAX_CAPACITY * 2));
        assertFalse(toAdd.isRetained());
        assertThat(buffer.size(), is(0L));
        toAdd.release();
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAddSmallByteBuffer(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        while (!buffer.isFull())
        {
            byte[] bytes = new byte[]{'-', 'X', '-'};
            ByteBuffer from = ByteBuffer.wrap(bytes, 1, 1);
            buffer.add(from);
        }

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("X".repeat(buffer.capacity())));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testNonRetainableWriteTo(Supplier<Mutable> supplier) throws Exception
    {
        Mutable buffer = supplier.get();
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
    public void testRetainableWriteTo(Supplier<Mutable> supplier) throws Exception
    {
        Mutable buffer = supplier.get();
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
    public void testCopyMutables(Supplier<Mutable> supplier)
    {
        Mutable original = supplier.get();
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
    public void testCopyMutablesThenModifyOriginal(Supplier<Mutable> supplier)
    {
        Mutable original = supplier.get();
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
    public void testPutPrimitives(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        // Test aligned
        buffer.putLong(0x00010203_04050607L);
        buffer.putInt(0x08090A0B);
        buffer.putShort((short)0x0C0D);
        buffer.put((byte)0x0E);
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase("000102030405060708090A0B0C0D0E"));

        // Test unaligned
        buffer.clear();
        buffer.putShort((short)0x1020);
        buffer.putInt(0x30405060);
        buffer.putLong(0x708090A0_B0C0D0E0L);

        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase("102030405060708090A0B0C0D0E0"));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testPutByte(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        while (buffer.space() >= 1)
            buffer.put((byte)0xAB);

        assertThrows(BufferOverflowException.class, () -> buffer.put((byte)'Z'));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "AbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAbAb"));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testPutShort(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        while (buffer.space() >= 2)
            buffer.putShort((short)0x1234);
        assertThrows(BufferOverflowException.class, () -> buffer.putShort((short)0xffff));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "12341234123412341234123412341234123412341234123412341234123412341234123412341234123412341234123412341234123412341234123412341234"));

        buffer.clear();
        buffer.put((byte)0);
        while (buffer.space() >= 2)
            buffer.putShort((short)0x1234);
        assertThrows(BufferOverflowException.class, () -> buffer.putShort((short)0xffff));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "001234123412341234123412341234123412341234123412341234123412341234123412341234123412341234123412341234123412341234123412341234"));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testPutInt(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        while (buffer.space() >= 4)
            buffer.putInt(0x1234ABCD);
        assertThrows(BufferOverflowException.class, () -> buffer.putInt(0xffffffff));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD"));

        buffer.clear();
        buffer.put((byte)0);
        while (buffer.space() >= 4)
            buffer.putInt(0x1234ABCD);
        assertThrows(BufferOverflowException.class, () -> buffer.putInt(0xffffffff));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "001234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD1234ABCD"));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testPutLong(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        while (buffer.space() >= 8)
            buffer.putLong(0x0123456789ABCDEFL);
        assertThrows(BufferOverflowException.class, () -> buffer.putLong(0xffffffffL));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"));

        buffer.clear();
        buffer.put((byte)0);
        while (buffer.space() >= 8)
            buffer.putLong(0x0123456789ABCDEFL);
        assertThrows(BufferOverflowException.class, () -> buffer.putLong(0xffffffffL));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "000123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testPutBytes(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        while (buffer.space() >= 7)
            buffer.put(StringUtil.fromHexString("000F1E2D3C4B5A6000"), 1, 7);
        assertThrows(BufferOverflowException.class, () -> buffer.put(StringUtil.fromHexString("000F1E2D3C4B5A6000"), 1, 7));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "0F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A60"));

        buffer.clear();
        buffer.put((byte)0xFF);
        buffer.put(StringUtil.fromHexString("0F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A"));
        assertThat(BufferUtil.toHexString(buffer.getByteBuffer()), equalToIgnoringCase(
            "FF0F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A600F1E2D3C4B5A"));

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testPutByteAtIndex(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.append(BufferUtil.toBuffer("Hello "));
        long size = buffer.size();
        buffer.add(BufferUtil.toBuffer("world!"));
        buffer.put(size, (byte)'W');
        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("Hello World!"));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.put(buffer.size() + 1, (byte)0));
        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testTakeByteBuffer(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        if (buffer instanceof RetainableByteBuffer.DynamicCapacity dynamic)
        {
            dynamic.put("Hello".getBytes(StandardCharsets.UTF_8));
            dynamic.put((byte)' ');
            CountDownLatch released = new CountDownLatch(1);
            dynamic.add(RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!".getBytes(StandardCharsets.UTF_8)), released::countDown));
            int length = dynamic.remaining();
            byte[] result = dynamic.takeByteArray();
            assertThat(new String(result, 0, length, StandardCharsets.UTF_8), is("Hello world!"));
            assertThat(buffer.remaining(), is(0));
        }

        buffer.release();
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testTake(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.put("Hello".getBytes(StandardCharsets.UTF_8));
        buffer.put((byte)' ');
        CountDownLatch released = new CountDownLatch(1);
        buffer.add(RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!".getBytes(StandardCharsets.UTF_8)), released::countDown));
        RetainableByteBuffer result = buffer.take();
        assertThat(BufferUtil.toString(result.getByteBuffer()), is("Hello world!"));
        assertThat(buffer.remaining(), is(0));
        result.release();
        assertTrue(buffer.release());
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testTakeRetained(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.put("Hello".getBytes(StandardCharsets.UTF_8));
        buffer.put((byte)' ');
        CountDownLatch released = new CountDownLatch(1);
        RetainableByteBuffer world = RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!".getBytes(StandardCharsets.UTF_8)), released::countDown);
        world.retain();
        buffer.add(world);

        RetainableByteBuffer result = buffer.take();
        assertThat(BufferUtil.toString(result.getByteBuffer()), is("Hello world!"));
        assertThat(buffer.remaining(), is(0));
        result.release();
        assertTrue(buffer.release());
        assertTrue(world.release());
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testTakeLength(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.put("Hello".getBytes(StandardCharsets.UTF_8));
        CountDownLatch released = new CountDownLatch(2);
        buffer.add(RetainableByteBuffer.wrap(BufferUtil.toBuffer(" cruel ".getBytes(StandardCharsets.UTF_8)), released::countDown));
        buffer.add(RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!".getBytes(StandardCharsets.UTF_8)), released::countDown));

        RetainableByteBuffer hello = buffer.take(5);
        assertThat(BufferUtil.toString(hello.getByteBuffer()), is("Hello"));
        assertFalse(hello.isRetained());
        assertFalse(buffer.isRetained());
        assertTrue(hello.release());

        RetainableByteBuffer space = buffer.take(1);
        assertThat(BufferUtil.toString(space.getByteBuffer()), is(" "));
        assertFalse(space.isRetained());
        assertTrue(space.release());

        RetainableByteBuffer cruel = buffer.take(5);
        assertThat(BufferUtil.toString(cruel.getByteBuffer()), is("cruel"));
        assertFalse(cruel.isRetained());
        assertTrue(cruel.release());

        RetainableByteBuffer world = buffer.take(6);
        assertThat(world.skip(1), is(1L));
        assertThat(BufferUtil.toString(world.getByteBuffer()), is("world"));
        assertFalse(world.isRetained());
        assertTrue(world.release());

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("!"));
        assertTrue(buffer.release());

        assertThat(released.getCount(), is(0L));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testTakeLengthRetained(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.put("Hello".getBytes(StandardCharsets.UTF_8));
        CountDownLatch released = new CountDownLatch(2);
        RetainableByteBuffer cruel = RetainableByteBuffer.wrap(BufferUtil.toBuffer(" cruel ".getBytes(StandardCharsets.UTF_8)), released::countDown);
        cruel.retain();
        buffer.add(cruel);
        RetainableByteBuffer world = RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!".getBytes(StandardCharsets.UTF_8)), released::countDown);
        world.retain();
        buffer.add(world);

        RetainableByteBuffer hello = buffer.take(5);
        RetainableByteBuffer cruelWorld = buffer.take(buffer.size() - 1);
        cruelWorld.skip(1);
        RetainableByteBuffer bang = buffer.take(1);

        assertThat(BufferUtil.toString(hello.getByteBuffer()), is("Hello"));
        assertThat(BufferUtil.toString(cruelWorld.getByteBuffer()), is("cruel world"));
        assertThat(BufferUtil.toString(bang.getByteBuffer()), is("!"));
        hello.release();
        cruelWorld.release();
        bang.release();
        assertTrue(buffer.release());
        assertTrue(cruel.release());
        assertTrue(world.release());
        assertThat(released.getCount(), is(0L));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testTakeFrom(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.put("Hello".getBytes(StandardCharsets.UTF_8));
        CountDownLatch released = new CountDownLatch(2);
        buffer.add(RetainableByteBuffer.wrap(BufferUtil.toBuffer(" cruel ".getBytes(StandardCharsets.UTF_8)), released::countDown));
        buffer.add(RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!".getBytes(StandardCharsets.UTF_8)), released::countDown));

        RetainableByteBuffer none = buffer.takeFrom(Long.MAX_VALUE);
        assertTrue(none.isEmpty());
        none.release();

        RetainableByteBuffer hello = buffer.takeFrom(0);
        buffer.release();

        RetainableByteBuffer space = hello.takeFrom(5);
        RetainableByteBuffer bang = space.takeFrom(space.size() - 1);
        RetainableByteBuffer cruelWorld = space.takeFrom(1);

        assertThat(BufferUtil.toString(hello.getByteBuffer()), is("Hello"));
        assertThat(BufferUtil.toString(space.getByteBuffer()), is(" "));
        assertThat(BufferUtil.toString(cruelWorld.getByteBuffer()), is("cruel world"));
        assertThat(BufferUtil.toString(bang.getByteBuffer()), is("!"));

        assertTrue(hello.release());
        space.release();
        cruelWorld.release();
        bang.release();

        assertThat(released.getCount(), is(0L));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testTakeFromRetained(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.put("Hello".getBytes(StandardCharsets.UTF_8));
        CountDownLatch released = new CountDownLatch(2);
        RetainableByteBuffer cruel = RetainableByteBuffer.wrap(BufferUtil.toBuffer(" cruel ".getBytes(StandardCharsets.UTF_8)), released::countDown);
        cruel.retain();
        buffer.add(cruel);
        RetainableByteBuffer world = RetainableByteBuffer.wrap(BufferUtil.toBuffer("world!".getBytes(StandardCharsets.UTF_8)), released::countDown);
        world.retain();
        buffer.add(world);
        RetainableByteBuffer space = buffer.takeFrom(5);

        RetainableByteBuffer bang = space.takeFrom(space.size() - 1);
        RetainableByteBuffer cruelWorld = space.takeFrom(1);

        assertThat(BufferUtil.toString(buffer.getByteBuffer()), is("Hello"));
        assertThat(BufferUtil.toString(space.getByteBuffer()), is(" "));
        assertThat(BufferUtil.toString(cruelWorld.getByteBuffer()), is("cruel world"));
        assertThat(BufferUtil.toString(bang.getByteBuffer()), is("!"));
        space.release();
        cruelWorld.release();
        bang.release();
        assertTrue(buffer.release());
        assertTrue(cruel.release());
        assertTrue(world.release());
        assertThat(released.getCount(), is(0L));
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAsMutable(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        assertThat(buffer.asMutable(), sameInstance(buffer));
        buffer.retain();
        assertThrows(ReadOnlyBufferException.class, buffer::asMutable);
        assertFalse(buffer.release());
        assertTrue(buffer.release());
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAppendEmpty(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        assertTrue(buffer.append(BufferUtil.EMPTY_BUFFER));
        assertTrue(buffer.append(RetainableByteBuffer.EMPTY));
        assertThat(buffer.remaining(), is(0));

        while (!buffer.isFull())
            buffer.append(BufferUtil.toBuffer("text to fill up the buffer"));

        long size = buffer.size();
        assertTrue(buffer.append(BufferUtil.EMPTY_BUFFER));
        assertTrue(buffer.append(RetainableByteBuffer.EMPTY));
        assertThat(buffer.size(), is(size));

        assertTrue(buffer.release());
    }

    @ParameterizedTest
    @MethodSource("mutables")
    public void testAddEmpty(Supplier<Mutable> supplier)
    {
        Mutable buffer = supplier.get();
        buffer.add(BufferUtil.EMPTY_BUFFER);
        buffer.add(RetainableByteBuffer.EMPTY);
        assertThat(buffer.remaining(), is(0));

        while (!buffer.isFull())
            buffer.append(BufferUtil.toBuffer("text to fill up the buffer"));

        long size = buffer.size();
        buffer.add(BufferUtil.EMPTY_BUFFER);
        buffer.add(RetainableByteBuffer.EMPTY);
        assertThat(buffer.size(), is(size));

        assertTrue(buffer.release());
    }
}
