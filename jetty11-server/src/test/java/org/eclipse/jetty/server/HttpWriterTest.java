//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpWriterTest
{
    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    private HttpOutput _httpOut;
    private ByteBuffer _bytes;

    @BeforeEach
    public void init() throws Exception
    {
        _bytes = BufferUtil.allocate(2048);

        final ByteBufferPool pool = new ArrayByteBufferPool();

        HttpChannel channel = new HttpChannel(new MockConnector(), new HttpConfiguration(), null, null)
        {
            @Override
            public boolean needContent()
            {
                return false;
            }

            @Override
            public HttpInput.Content produceContent()
            {
                return null;
            }

            @Override
            public boolean failAllContent(Throwable failure)
            {
                return false;
            }

            @Override
            public ByteBufferPool getByteBufferPool()
            {
                return pool;
            }

            @Override
            public boolean failed(Throwable x)
            {
                return false;
            }

            @Override
            protected boolean eof()
            {
                return false;
            }
        };

        _httpOut = new HttpOutput(channel)
        {
            @Override
            public void write(byte[] b, int off, int len) throws IOException
            {
                BufferUtil.append(_bytes, b, off, len);
            }
        };
    }

    @Test
    public void testSimpleUTF8() throws Exception
    {
        HttpWriter writer = new Utf8HttpWriter(_httpOut);
        writer.write("Now is the time");
        assertArrayEquals("Now is the time".getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testUTF8() throws Exception
    {
        HttpWriter writer = new Utf8HttpWriter(_httpOut);
        writer.write("How now \uFF22rown cow");
        assertArrayEquals("How now \uFF22rown cow".getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testUTF16() throws Exception
    {
        HttpWriter writer = new EncodingHttpWriter(_httpOut, StringUtil.__UTF16);
        writer.write("How now \uFF22rown cow");
        assertArrayEquals("How now \uFF22rown cow".getBytes(StandardCharsets.UTF_16), BufferUtil.toArray(_bytes));
    }

    @Test
    public void testNotCESU8() throws Exception
    {
        HttpWriter writer = new Utf8HttpWriter(_httpOut);
        String data = "xxx\uD801\uDC00xxx";
        writer.write(data);
        assertEquals("787878F0909080787878", TypeUtil.toHexString(BufferUtil.toArray(_bytes)));
        assertArrayEquals(data.getBytes(StandardCharsets.UTF_8), BufferUtil.toArray(_bytes));
        assertEquals(3 + 4 + 3, _bytes.remaining());

        Utf8StringBuilder buf = new Utf8StringBuilder();
        buf.append(BufferUtil.toArray(_bytes), 0, _bytes.remaining());
        assertEquals(data, buf.toString());
    }

    @Test
    public void testMultiByteOverflowUTF8() throws Exception
    {
        HttpWriter writer = new Utf8HttpWriter(_httpOut);
        final String singleByteStr = "a";
        final String multiByteDuplicateStr = "\uFF22";
        int remainSize = 1;

        int multiByteStrByteLength = multiByteDuplicateStr.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HttpWriter.MAX_OUTPUT_CHARS - multiByteStrByteLength; i++)
        {
            sb.append(singleByteStr);
        }
        sb.append(multiByteDuplicateStr);
        for (int i = 0; i < remainSize; i++)
        {
            sb.append(singleByteStr);
        }
        char[] buf = new char[HttpWriter.MAX_OUTPUT_CHARS * 3];

        int length = HttpWriter.MAX_OUTPUT_CHARS - multiByteStrByteLength + remainSize + 1;
        sb.toString().getChars(0, length, buf, 0);

        writer.write(buf, 0, length);

        assertEquals(sb.toString(), new String(BufferUtil.toArray(_bytes), StandardCharsets.UTF_8));
    }

    @Test
    public void testISO8859() throws Exception
    {
        HttpWriter writer = new Iso88591HttpWriter(_httpOut);
        writer.write("How now \uFF22rown cow");
        assertEquals(new String(BufferUtil.toArray(_bytes), StandardCharsets.ISO_8859_1), "How now ?rown cow");
    }

    @Test
    public void testUTF16x2() throws Exception
    {
        HttpWriter writer = new Utf8HttpWriter(_httpOut);

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
        HttpWriter writer = new Utf8HttpWriter(_httpOut);

        final String singleByteStr = "a";
        int remainSize = 1;
        final String multiByteDuplicateStr = "\uD842\uDF9F";
        int adjustSize = -1;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HttpWriter.MAX_OUTPUT_CHARS + adjustSize; i++)
        {
            sb.append(singleByteStr);
        }
        sb.append(multiByteDuplicateStr);
        for (int i = 0; i < remainSize; i++)
        {
            sb.append(singleByteStr);
        }
        String source = sb.toString();

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
        HttpWriter writer = new Utf8HttpWriter(_httpOut);

        final String singleByteStr = "a";
        int remainSize = 1;
        final String multiByteDuplicateStr = "\uD842\uDF9F";
        int adjustSize = -2;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < HttpWriter.MAX_OUTPUT_CHARS + adjustSize; i++)
        {
            sb.append(singleByteStr);
        }
        sb.append(multiByteDuplicateStr);
        for (int i = 0; i < remainSize; i++)
        {
            sb.append(singleByteStr);
        }
        String source = sb.toString();

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
//        for (int i = 0; i < bytes.length; i++)
//        {
//            System.err.format("%s%x",(i == 0)?"[":(i % (HttpWriter.MAX_OUTPUT_CHARS) == 0)?"][":",",bytes[i]);
//        }
//        System.err.format("]->%s\n",new String(bytes,StringUtil.__UTF8));
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
