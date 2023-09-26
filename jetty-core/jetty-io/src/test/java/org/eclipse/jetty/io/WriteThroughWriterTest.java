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
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class WriteThroughWriterTest
{
    private OutputStream _out;
    private ByteBuffer _bytes;

    @BeforeEach
    public void init() throws Exception
    {
        _bytes = BufferUtil.allocate(2048);
        _out = new ByteBufferOutputStream(_bytes);
    }

    @Test
    public void testSimpleUTF8() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.UTF_8);
        writer.write("Now is the time");
        assertArrayEquals("Now is the time".getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testUTF8() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.UTF_8);
        writer.write("How now \uFF22rown cow");
        assertArrayEquals("How now \uFF22rown cow".getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testUTF16() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.UTF_16);
        writer.write("How now \uFF22rown cow");
        assertArrayEquals("How now \uFF22rown cow".getBytes(StandardCharsets.UTF_16), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testNotCESU8() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.UTF_8);
        String data = "xxx\uD801\uDC00xxx";
        writer.write(data);
        byte[] b = BufferUtil.toArray(_bytes);
        assertEquals("787878F0909080787878", StringUtil.toHexString(b));
        assertArrayEquals(data.getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
        assertEquals(3 + 4 + 3, _bytes.remaining());

        Utf8StringBuilder buf = new Utf8StringBuilder();
        buf.append(BufferUtil.toArray(_bytes), 0, _bytes.remaining());
        assertEquals(data, buf.takeCompleteString(Utf8StringBuilder.Utf8CharacterCodingException::new));
    }

    @Test
    public void testMultiByteOverflowUTF8() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.UTF_8);
        int maxWriteSize = WriteThroughWriter.DEFAULT_MAX_WRITE_SIZE;
        final String singleByteStr = "a";
        final String multiByteDuplicateStr = "\uFF22";
        int remainSize = 1;

        int multiByteStrByteLength = multiByteDuplicateStr.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder();
        sb.append(singleByteStr.repeat(Math.max(0, maxWriteSize - multiByteStrByteLength)));
        sb.append(multiByteDuplicateStr);
        sb.append(singleByteStr.repeat(remainSize));
        char[] buf = new char[maxWriteSize * 3];

        int length = maxWriteSize - multiByteStrByteLength + remainSize + 1;
        sb.toString().getChars(0, length, buf, 0);

        writer.write(buf, 0, length);

        assertEquals(sb.toString(), new String(BufferUtil.toArray(_bytes), StandardCharsets.UTF_8));
    }

    @Test
    public void testISO8859() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.ISO_8859_1);
        writer.write("How now \uFF22rown cow");
        assertEquals(new String(BufferUtil.toArray(_bytes), StandardCharsets.ISO_8859_1), "How now ?rown cow");
    }

    @Test
    public void testUTF16x2() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.UTF_8);
        String source = "\uD842\uDF9F";

        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        writer.write(source.toCharArray(), 0, source.toCharArray().length);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(baos, StandardCharsets.UTF_8);
        osw.write(source.toCharArray(), 0, source.toCharArray().length);
        osw.flush();

        myReportBytes(bytes);
        myReportBytes(baos.toByteArray());
        myReportBytes(BufferUtil.toArray(_bytes));

        assertArrayEquals(bytes, BufferUtil.toArray(_bytes));
        assertArrayEquals(baos.toByteArray(), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testMultiByteOverflowUTF16x2() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.UTF_8);

        final String singleByteStr = "a";
        int remainSize = 1;
        final String multiByteDuplicateStr = "\uD842\uDF9F";
        int adjustSize = -1;

        String source =
            singleByteStr.repeat(Math.max(0, WriteThroughWriter.DEFAULT_MAX_WRITE_SIZE + adjustSize)) +
            multiByteDuplicateStr +
            singleByteStr.repeat(remainSize);

        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        writer.write(source.toCharArray(), 0, source.toCharArray().length);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(baos, StandardCharsets.UTF_8);
        osw.write(source.toCharArray(), 0, source.toCharArray().length);
        osw.flush();

        myReportBytes(bytes);
        myReportBytes(baos.toByteArray());
        myReportBytes(BufferUtil.toArray(_bytes));

        assertArrayEquals(bytes, BufferUtil.toArray(_bytes));
        assertArrayEquals(baos.toByteArray(), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testMultiByteOverflowUTF16X22() throws Exception
    {
        Writer writer = WriteThroughWriter.newWriter(_out, StandardCharsets.UTF_8);

        final String singleByteStr = "a";
        int remainSize = 1;
        final String multiByteDuplicateStr = "\uD842\uDF9F";
        int adjustSize = -2;

        String source =
            singleByteStr.repeat(Math.max(0, WriteThroughWriter.DEFAULT_MAX_WRITE_SIZE + adjustSize)) +
            multiByteDuplicateStr +
            singleByteStr.repeat(remainSize);

        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        writer.write(source.toCharArray(), 0, source.toCharArray().length);

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        java.io.OutputStreamWriter osw = new java.io.OutputStreamWriter(baos, StandardCharsets.UTF_8);
        osw.write(source.toCharArray(), 0, source.toCharArray().length);
        osw.flush();

        myReportBytes(bytes);
        myReportBytes(baos.toByteArray());
        myReportBytes(BufferUtil.toArray(_bytes));

        assertArrayEquals(bytes, BufferUtil.toArray(_bytes));
        assertArrayEquals(baos.toByteArray(), BufferUtil.toArray(_bytes));
    }

    private void myReportBytes(byte[] bytes) throws Exception
    {
        if (LoggerFactory.getLogger(WriteThroughWriterTest.class).isDebugEnabled())
        {
            for (int i = 0; i < bytes.length; i++)
                System.err.format("%s%x", (i == 0) ? "[" : (i % 512 == 0) ? "][" : ",", bytes[i]);
            System.err.format("]->%s\n", new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private void assertArrayEquals(byte[] b1, byte[] b2)
    {
        String test = new String(b1) + "==" + new String(b2);
        assertEquals(b1.length, b2.length, test);
        for (int i = 0; i < b1.length; i++)
        {
            assertEquals(b1[i], b2[i], test);
        }
    }

    public static Stream<Arguments> subSequenceTests()
    {
        return Stream.of(
            Arguments.of("", 0, 0, ""),
            Arguments.of("", 0, 1, null),
            Arguments.of("", 1, 0, ""),
            Arguments.of("", 1, 1, null),
            Arguments.of("hello", 0, 5, "hello"),
            Arguments.of("hello", 0, 4, "hell"),
            Arguments.of("hello", 1, 4, "ello"),
            Arguments.of("hello", 1, 3, "ell"),
            Arguments.of("hello", 5, 0, ""),
            Arguments.of("hello", 0, 6, null)
        );
    }

    @ParameterizedTest
    @MethodSource("subSequenceTests")
    public void testSubSequence(String source, int offset, int length, String expected)
    {
        if (expected == null)
        {
            assertThrows(IndexOutOfBoundsException.class, () -> WriteThroughWriter.subSequence(source, offset, length));
            assertThrows(IndexOutOfBoundsException.class, () -> WriteThroughWriter.subSequence(source.toCharArray(), offset, length));
            return;
        }

        CharSequence result = WriteThroughWriter.subSequence(source, offset, length);
        assertThat(result.toString(), equalTo(expected));

        // check string optimization
        if (offset == 0 && length == source.length())
        {
            assertThat(result, sameInstance(source));
            assertThat(result.subSequence(offset, length), sameInstance(source));
            return;
        }

        result = WriteThroughWriter.subSequence(source.toCharArray(), offset, length);
        assertThat(result.toString(), equalTo(expected));
    }
}
