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

package org.eclipse.jetty.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated
public class BufferUtilTest
{
    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testSlice()
    {
        ByteBuffer byteBuffer = ByteBuffer.wrap("0123456789".getBytes(StandardCharsets.UTF_8));
        assertEquals("0123456789", BufferUtil.toString(BufferUtil.slice(byteBuffer, 0, -1)));
        assertEquals("3456789", BufferUtil.toString(BufferUtil.slice(byteBuffer, 3, -1)));
        assertEquals("01234567", BufferUtil.toString(BufferUtil.slice(byteBuffer, 0, 8)));
        assertEquals("5678", BufferUtil.toString(BufferUtil.slice(byteBuffer, 5, 4)));
        assertEquals("", BufferUtil.toString(BufferUtil.slice(byteBuffer, 1, 0)));
        assertEquals("", BufferUtil.toString(BufferUtil.slice(byteBuffer, 10, -1)));
        assertEquals("", BufferUtil.toString(BufferUtil.slice(byteBuffer, 1000, 0)));

        assertThrows(IllegalArgumentException.class, () -> BufferUtil.slice(byteBuffer, 0, 11));
        assertThrows(IllegalArgumentException.class, () -> BufferUtil.slice(byteBuffer, 11, -1));
        assertThrows(IllegalArgumentException.class, () -> BufferUtil.slice(byteBuffer, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> BufferUtil.slice(byteBuffer, -1, 1));
    }

    @Test
    public void testSliceCollection()
    {
        ByteBuffer empty = ByteBuffer.allocate(0);
        ByteBuffer one = ByteBuffer.wrap("1".getBytes(StandardCharsets.UTF_8));
        ByteBuffer digits = ByteBuffer.wrap("0123456789".getBytes(StandardCharsets.UTF_8));
        ByteBuffer alpha = ByteBuffer.wrap("abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));

        Collection<ByteBuffer> slices = BufferUtil.slice(List.of(), 0, 0);
        assertThat(slices.size(), is(0));

        slices = BufferUtil.slice(List.of(empty), 0, 0);
        assertThat(slices.size(), is(0));

        slices = BufferUtil.slice(List.of(one), 0, 0);
        assertThat(slices.size(), is(0));

        slices = BufferUtil.slice(List.of(empty, one, digits), 0, 0);
        assertThat(slices.size(), is(0));

        Throwable thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(), 1, 0));
        assertThat(thrown.getMessage(), containsString("offset too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty), 1, 0));
        assertThat(thrown.getMessage(), containsString("offset too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, empty), 1, 0));
        assertThat(thrown.getMessage(), containsString("offset too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, one, empty), 2, 0));
        assertThat(thrown.getMessage(), containsString("offset too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, one, alpha), 28, 0));
        assertThat(thrown.getMessage(), containsString("offset too large"));

        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(), 0, 1));
        assertThat(thrown.getMessage(), containsString("length too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty), 0, 1));
        assertThat(thrown.getMessage(), containsString("length too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, empty), 0, 1));
        assertThat(thrown.getMessage(), containsString("length too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, one, empty), 0, 2));
        assertThat(thrown.getMessage(), containsString("length too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, one, alpha), 0, 28));
        assertThat(thrown.getMessage(), containsString("length too large"));

        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(one), 1, 1));
        assertThat(thrown.getMessage(), containsString("length too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(one), 1, 1));
        assertThat(thrown.getMessage(), containsString("length too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, one, empty), 1, 1));
        assertThat(thrown.getMessage(), containsString("length too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, one, digits), 1, 11));
        assertThat(thrown.getMessage(), containsString("length too large"));
        thrown = assertThrows(IndexOutOfBoundsException.class, () -> BufferUtil.slice(List.of(empty, digits, alpha), 10, 27));
        assertThat(thrown.getMessage(), containsString("length too large"));

        String sliced = BufferUtil.slice(List.of(empty, one), 0, 1).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("1"));
        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 0, 10).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("1012345678"));
        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 1, 10).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("0123456789"));
        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 1, 11).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("0123456789a"));

        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 0, -1).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("10123456789abcdefghijklmnopqrstuvwxyz"));
        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 1, -1).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("0123456789abcdefghijklmnopqrstuvwxyz"));
        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 11, -1).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("abcdefghijklmnopqrstuvwxyz"));
        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 12, -1).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("bcdefghijklmnopqrstuvwxyz"));
        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 36, -1).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is("z"));
        sliced = BufferUtil.slice(List.of(empty, one, digits, empty, alpha), 37, -1).stream().map(BufferUtil::toString).reduce("", (a, b) -> a + b);
        assertThat(sliced, is(""));
    }

    @Test
    public void testToInt()
    {
        ByteBuffer[] buf =
            {
                BufferUtil.toBuffer("0"),
                BufferUtil.toBuffer(" 42 "),
                BufferUtil.toBuffer("   43abc"),
                BufferUtil.toBuffer("-44"),
                BufferUtil.toBuffer(" - 45;"),
                BufferUtil.toBuffer("-2147483648"),
                BufferUtil.toBuffer("2147483647"),
            };

        int[] val =
            {
                0, 42, 43, -44, -45, -2147483648, 2147483647
            };

        for (int i = 0; i < buf.length; i++)
        {
            assertEquals(val[i], BufferUtil.toInt(buf[i]), "t" + i);
        }
    }

    @Test
    public void testPutInt()
    {
        int[] val =
            {
                0, 42, 43, -44, -45, Integer.MIN_VALUE, Integer.MAX_VALUE
            };

        String[] str =
            {
                "0", "42", "43", "-44", "-45", "" + Integer.MIN_VALUE, "" + Integer.MAX_VALUE
            };

        ByteBuffer buffer = ByteBuffer.allocate(24);

        for (int i = 0; i < val.length; i++)
        {
            BufferUtil.clearToFill(buffer);
            BufferUtil.putDecInt(buffer, val[i]);
            BufferUtil.flipToFlush(buffer, 0);
            assertEquals(str[i], BufferUtil.toString(buffer), "t" + i);
        }
    }

    @Test
    public void testPutLong()
    {
        long[] val =
            {
                0L, 42L, 43L, -44L, -45L, Long.MIN_VALUE, Long.MAX_VALUE
            };

        String[] str =
            {
                "0", "42", "43", "-44", "-45", "" + Long.MIN_VALUE, "" + Long.MAX_VALUE
            };

        ByteBuffer buffer = ByteBuffer.allocate(50);

        for (int i = 0; i < val.length; i++)
        {
            BufferUtil.clearToFill(buffer);
            BufferUtil.putDecLong(buffer, val[i]);
            BufferUtil.flipToFlush(buffer, 0);
            assertEquals(str[i], BufferUtil.toString(buffer), "t" + i);
        }
    }

    @Test
    public void testPutHexInt()
    {
        int[] val =
            {
                0, 42, 43, -44, -45, -2147483648, 2147483647
            };

        String[] str =
            {
                "0", "2A", "2B", "-2C", "-2D", "-80000000", "7FFFFFFF"
            };

        ByteBuffer buffer = ByteBuffer.allocate(50);

        for (int i = 0; i < val.length; i++)
        {
            BufferUtil.clearToFill(buffer);
            BufferUtil.putHexInt(buffer, val[i]);
            BufferUtil.flipToFlush(buffer, 0);
            assertEquals(str[i], BufferUtil.toString(buffer), "t" + i);
        }
    }

    @Test
    public void testPutFromReadableBufferIntoLargeEnoughBuffer()
    {
        ReadableBuffer from = ReadableBuffer.wrap(BufferUtil.toBuffer("012345"));
        ByteBuffer to = BufferUtil.allocate(10);

        BufferUtil.put(from, to);
        assertEquals(0, from.remaining());
        assertEquals(6, to.remaining());
        assertEquals("012345", BufferUtil.toString(to));
    }

    @Test
    public void testPutFromReadableBufferIntoTooSmallBuffer()
    {
        ReadableBuffer from = ReadableBuffer.wrap(BufferUtil.toBuffer("1234567890A"));
        ByteBuffer to = BufferUtil.allocate(10);

        BufferUtil.put(from, to);
        assertEquals(1, from.remaining());
        assertEquals(10, to.remaining());
        assertEquals("1234567890", BufferUtil.toString(to));
        assertEquals('A', from.get());
    }

    @Test
    public void testPut()
    {
        ByteBuffer to = BufferUtil.allocate(10);
        ByteBuffer from = BufferUtil.toBuffer("12345");

        BufferUtil.clear(to);
        assertEquals(5, BufferUtil.append(to, from));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345", BufferUtil.toString(to));

        from = BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5, BufferUtil.append(to, from));
        assertEquals(2, from.remaining());
        assertEquals("1234567890", BufferUtil.toString(to));
    }

    @Test
    public void testAppend()
    {
        ByteBuffer to = BufferUtil.allocate(8);
        ByteBuffer from = BufferUtil.toBuffer("12345");

        BufferUtil.append(to, from.array(), 0, 3);
        assertEquals("123", BufferUtil.toString(to));
        BufferUtil.append(to, from.array(), 3, 2);
        assertEquals("12345", BufferUtil.toString(to));

        assertThrows(BufferOverflowException.class, () -> BufferUtil.append(to, from.array(), 0, 5));
    }

    @Test
    public void testPutDirect()
    {
        ByteBuffer to = BufferUtil.allocateDirect(10);
        ByteBuffer from = BufferUtil.toBuffer("12345");

        BufferUtil.clear(to);
        assertEquals(5, BufferUtil.append(to, from));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345", BufferUtil.toString(to));

        from = BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5, BufferUtil.append(to, from));
        assertEquals(2, from.remaining());
        assertEquals("1234567890", BufferUtil.toString(to));
    }

    @Test
    public void testToBufferArray()
    {
        byte[] arr = new byte[128];
        Arrays.fill(arr, (byte)0x44);
        ByteBuffer buf = BufferUtil.toBuffer(arr);

        int count = 0;
        while (buf.remaining() > 0)
        {
            byte b = buf.get();
            assertEquals(0x44, b);
            count++;
        }

        assertEquals(arr.length, count, "Count of bytes");
    }

    @Test
    public void testToBufferArrayOffsetLength()
    {
        byte[] arr = new byte[128];
        Arrays.fill(arr, (byte)0xFF); // fill the whole thing with FF
        int offset = 10;
        int length = 100;
        Arrays.fill(arr, offset, offset + length, (byte)0x77); // fill partial with 0x77
        ByteBuffer buf = BufferUtil.toBuffer(arr, offset, length);

        int count = 0;
        while (buf.remaining() > 0)
        {
            byte b = buf.get();
            assertEquals(0x77, b);
            count++;
        }

        assertEquals(length, count, "Count of bytes");
    }

    @Test
    public void testWriteToWithBufferThatDoesNotExposeArrayAndSmallContent() throws IOException
    {
        int capacity = BufferUtil.TEMP_BUFFER_SIZE / 4;
        testWriteToWithBufferThatDoesNotExposeArray(capacity);
    }

    @Test
    public void testWriteToWithBufferThatDoesNotExposeArrayAndContentLengthMatchingTempBufferSize() throws IOException
    {
        int capacity = BufferUtil.TEMP_BUFFER_SIZE;
        testWriteToWithBufferThatDoesNotExposeArray(capacity);
    }

    @Test
    public void testWriteToWithBufferThatDoesNotExposeArrayAndContentSlightlyBiggerThanTwoTimesTempBufferSize()
        throws
        IOException
    {
        int capacity = BufferUtil.TEMP_BUFFER_SIZE * 2 + 1024;
        testWriteToWithBufferThatDoesNotExposeArray(capacity);
    }

    @Test
    @SuppressWarnings("ReferenceEquality")
    public void testEnsureCapacity()
    {
        ByteBuffer b = BufferUtil.toBuffer("Goodbye Cruel World");
        assertSame(b, BufferUtil.ensureCapacity(b, 0));
        assertSame(b, BufferUtil.ensureCapacity(b, 10));
        assertSame(b, BufferUtil.ensureCapacity(b, b.capacity()));

        ByteBuffer b1 = BufferUtil.ensureCapacity(b, 64);
        assertNotSame(b, b1);
        assertEquals(64, b1.capacity());
        assertEquals("Goodbye Cruel World", BufferUtil.toString(b1));

        b1.position(8);
        b1.limit(13);
        assertEquals("Cruel", BufferUtil.toString(b1));
        ByteBuffer b2 = b1.slice();
        assertEquals("Cruel", BufferUtil.toString(b2));
        System.err.println(BufferUtil.toDetailString(b2));
        assertEquals(8, b2.arrayOffset());
        assertEquals(5, b2.capacity());

        assertSame(b2, BufferUtil.ensureCapacity(b2, 5));

        ByteBuffer b3 = BufferUtil.ensureCapacity(b2, 64);
        assertNotSame(b2, b3);
        assertEquals(64, b3.capacity());
        assertEquals("Cruel", BufferUtil.toString(b3));
        assertEquals(0, b3.arrayOffset());
    }

    @Test
    public void testToDetailWithDEL()
    {
        ByteBuffer b = ByteBuffer.allocate(40);
        b.putChar('a').putChar('b').putChar('c');
        b.put((byte)0x7F);
        b.putChar('x').putChar('y').putChar('z');
        b.flip();
        String result = BufferUtil.toDetailString(b);
        assertThat("result", result, containsString("\\x7f"));
    }

    @Test
    public void testCopyIndirect()
    {
        ByteBuffer b = BufferUtil.toBuffer("Hello World");
        ByteBuffer c = BufferUtil.copy(b);
        assertEquals("Hello World", BufferUtil.toString(c));
        assertFalse(c.isDirect());
        assertThat(b, not(sameInstance(c)));
        assertThat(b.array(), not(sameInstance(c.array())));
    }

    @Test
    public void testCopyDirect()
    {
        ByteBuffer b = BufferUtil.allocateDirect(11);
        BufferUtil.append(b, "Hello World");
        ByteBuffer c = BufferUtil.copy(b);
        assertEquals("Hello World", BufferUtil.toString(c));
        assertTrue(c.isDirect());
        assertThat(b, not(sameInstance(c)));
    }

    private void testWriteToWithBufferThatDoesNotExposeArray(int capacity) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] bytes = new byte[capacity];
        ThreadLocalRandom.current().nextBytes(bytes);
        ByteBuffer buffer = BufferUtil.allocate(capacity);
        BufferUtil.append(buffer, bytes, 0, capacity);
        BufferUtil.writeTo(buffer.asReadOnlyBuffer(), out);
        assertThat("Bytes in out equal bytes in buffer", Arrays.equals(bytes, out.toByteArray()), is(true));
    }

    @Test
    public void testToMappedBufferResource() throws Exception
    {
        Path testZip = MavenTestingUtils.getTestResourcePathFile("TestData/test.zip");
        Path testTxt = MavenTestingUtils.getTestResourcePathFile("TestData/alphabet.txt");

        Resource fileResource = ResourceFactory.root().newResource(testTxt.toUri());
        ByteBuffer fileBuffer = BufferUtil.toMappedBuffer(fileResource);
        assertThat(fileBuffer, not(nullValue()));
        assertThat((long)fileBuffer.remaining(), is(fileResource.length()));

        Resource jrtResource = ResourceFactory.root().newResource("jrt:/java.base/java/lang/Object.class");
        assertThat(jrtResource.exists(), is(true));
        assertThat(BufferUtil.toMappedBuffer(jrtResource), nullValue());

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource jarResource = resourceFactory.newJarFileResource(testZip.toUri());
            assertThat(BufferUtil.toMappedBuffer(jarResource), nullValue());
        }
    }

    @Test
    public void testToMappedBufferOnUnsupportedFileSystem() throws Exception
    {
        Path jarFile = MavenTestingUtils.getTestResourcePathFile("example.jar");

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Path jarPath = resourceFactory.newJarFileResource(jarFile.toUri()).resolve("WEB-INF/web.xml").getPath();
            assertThat(BufferUtil.toMappedBuffer(jarPath), nullValue());
        }
    }
}
