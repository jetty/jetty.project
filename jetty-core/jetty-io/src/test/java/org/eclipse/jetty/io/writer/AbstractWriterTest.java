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

package org.eclipse.jetty.io.writer;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.AbstractOutputStreamWriter;
import org.eclipse.jetty.io.ByteBufferOutputStream;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AbstractWriterTest
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
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.Utf8Writer(_out);
        writer.write("Now is the time");
        assertArrayEquals("Now is the time".getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testUTF8() throws Exception
    {
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.Utf8Writer(_out);
        writer.write("How now Ｂrown cow");
        assertArrayEquals("How now Ｂrown cow".getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testUTF16() throws Exception
    {
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.EncodingWriter(_out, StandardCharsets.UTF_16.displayName());
        writer.write("How now Ｂrown cow");
        assertArrayEquals("How now Ｂrown cow".getBytes(StandardCharsets.UTF_16), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testNotCESU8() throws Exception
    {
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.Utf8Writer(_out);
        String data = "xxx𐐀xxx";
        writer.write(data);
        byte[] b = BufferUtil.toArray(_bytes);
        assertEquals("787878F0909080787878", StringUtil.toHexString(b));
        assertArrayEquals(data.getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
        assertEquals(3 + 4 + 3, _bytes.remaining());

        Utf8StringBuilder buf = new Utf8StringBuilder();
        buf.append(BufferUtil.toArray(_bytes), 0, _bytes.remaining());
        assertEquals(data, buf.takeCompleteString(IllegalArgumentException::new));
    }

    @Test
    public void testMultiByteOverflowUTF8() throws Exception
    {
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.Utf8Writer(_out);
        int maxWriteSize = writer.getMaxWriteSize();
        final String singleByteStr = "a";
        final String multiByteDuplicateStr = "Ｂ";
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
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.Iso88591Writer(_out);
        writer.write("How now Ｂrown cow");
        assertEquals(new String(BufferUtil.toArray(_bytes), StandardCharsets.ISO_8859_1), "How now ?rown cow");
    }

    @Test
    public void testUTF16x2() throws Exception
    {
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.Utf8Writer(_out);
        String source = "𠮟";

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
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.Utf8Writer(_out);

        final String singleByteStr = "a";
        int remainSize = 1;
        final String multiByteDuplicateStr = "𠮟";
        int adjustSize = -1;

        String source =
            singleByteStr.repeat(Math.max(0, writer.getMaxWriteSize() + adjustSize)) +
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
        AbstractOutputStreamWriter writer = new AbstractOutputStreamWriter.Utf8Writer(_out);

        final String singleByteStr = "a";
        int remainSize = 1;
        final String multiByteDuplicateStr = "𠮟";
        int adjustSize = -2;

        String source =
            singleByteStr.repeat(Math.max(0, writer.getMaxWriteSize() + adjustSize)) +
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
        if (LoggerFactory.getLogger(AbstractWriterTest.class).isDebugEnabled())
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
}
