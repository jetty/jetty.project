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

package org.eclipse.jetty.http;

import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpGeneratorClientTest
{
    public static final String[] connect = {null, "keep-alive", "close"};

    class RequestInfo extends MetaData.Request
    {
        RequestInfo(String method, String uri, HttpFields fields)
        {
            super(method, HttpURI.from(method, uri), HttpVersion.HTTP_1_1, fields);
        }

        RequestInfo(String method, String uri, HttpVersion version, HttpFields fields)
        {
            super(method, HttpURI.from(method, uri), version, fields);
        }

        RequestInfo(String method, String uri, int contentLength, HttpFields fields)
        {
            super(method, HttpURI.from(method, uri), HttpVersion.HTTP_1_1, fields, contentLength);
        }
    }

    @Test
    public void testGETRequestNoContent() throws Exception
    {
        WritableBuffer header = WritableBuffer.allocate(2048, false);
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "something");
        fields.add("User-Agent", "test");
        RequestInfo info = new RequestInfo("GET", "/index.html", fields);
        assertFalse(gen.isChunking());

        result = gen.generateRequest(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertFalse(gen.isChunking());
        String out = BufferUtil.toString(header.toReadable());

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertFalse(gen.isChunking());

        assertEquals(0, gen.getContentPrepared());
        assertThat(out, Matchers.containsString("GET /index.html HTTP/1.1"));
        assertThat(out, Matchers.not(Matchers.containsString("Content-Length")));
    }

    @Test
    public void testEmptyHeaders() throws Exception
    {
        WritableBuffer header = WritableBuffer.allocate(2048, false);
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "something");
        fields.add("Null", (String)null);
        fields.add("Null", (List<String>)null);
        assertThat(fields.size(), equalTo(1));
        fields.add("Empty", "");
        RequestInfo info = new RequestInfo("GET", "/index.html", fields);
        assertFalse(gen.isChunking());

        result = gen.generateRequest(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());
        assertFalse(gen.isChunking());

        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertFalse(gen.isChunking());
        String out = BufferUtil.toString(header.toReadable());

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertFalse(gen.isChunking());

        assertEquals(0, gen.getContentPrepared());
        assertThat(out, Matchers.containsString("GET /index.html HTTP/1.1"));
        assertThat(out, Matchers.not(Matchers.containsString("Content-Length")));
        assertThat(out, Matchers.containsString("Empty:"));
        assertThat(out, Matchers.not(Matchers.containsString("Null:")));
    }

    @Test
    public void testHeaderOverflow() throws Exception
    {
        HttpGenerator gen = new HttpGenerator();

        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "localhost");
        fields.add("Field", "SomeWhatLongValue");
        RequestInfo info = new RequestInfo("GET", "/index.html", HttpVersion.HTTP_1_0, fields);

        HttpGenerator.Result result = gen.generateRequest(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);

        WritableBuffer header = WritableBuffer.allocate(16, false);
        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.HEADER_OVERFLOW, result);

        header = WritableBuffer.allocate(2048, false);
        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertFalse(gen.isChunking());
        String out = BufferUtil.toString(header.toReadable());

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.SHUTDOWN_OUT, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertFalse(gen.isChunking());

        assertEquals(0, gen.getContentPrepared());
        assertThat(out, Matchers.containsString("GET /index.html HTTP/1.0"));
        assertThat(out, Matchers.not(Matchers.containsString("Content-Length")));
        assertThat(out, Matchers.containsString("Field: SomeWhatLongValue"));
    }

    @Test
    public void testPOSTRequestNoContent() throws Exception
    {
        WritableBuffer header = WritableBuffer.allocate(2048, false);
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "something");
        fields.add("User-Agent", "test");
        RequestInfo info = new RequestInfo("POST", "/index.html", fields);
        assertFalse(gen.isChunking());

        result = gen.generateRequest(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertFalse(gen.isChunking());
        String out = BufferUtil.toString(header.toReadable());

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertFalse(gen.isChunking());

        assertEquals(0, gen.getContentPrepared());
        assertThat(out, Matchers.containsString("POST /index.html HTTP/1.1"));
        assertThat(out, Matchers.containsString("Content-Length: 0"));
    }

    @Test
    public void testRequestWithContent() throws Exception
    {
        String out;
        WritableBuffer header = WritableBuffer.allocate(4096, false);
        ReadableBuffer content0 = BufferUtil.toReadableBuffer("Hello World. The quick brown fox jumped over the lazy dog.");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, content0, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "something");
        fields.add("User-Agent", "test");
        RequestInfo info = new RequestInfo("POST", "/index.html", fields);

        result = gen.generateRequest(info, null, null, content0, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, content0, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertFalse(gen.isChunking());
        out = BufferUtil.toString(header.toReadable());
        out += BufferUtil.toString(content0);

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertFalse(gen.isChunking());

        assertThat(out, Matchers.containsString("POST /index.html HTTP/1.1"));
        assertThat(out, Matchers.containsString("Host: something"));
        assertThat(out, Matchers.containsString("Content-Length: 58"));
        assertThat(out, Matchers.containsString("Hello World. The quick brown fox jumped over the lazy dog."));

        assertEquals(58, gen.getContentPrepared());
    }

    @Test
    public void testRequestWithChunkedContent() throws Exception
    {
        String out;
        WritableBuffer header = WritableBuffer.allocate(4096, false);
        WritableBuffer chunk = WritableBuffer.allocate(HttpGenerator.CHUNK_SIZE, false);
        ReadableBuffer content0 = BufferUtil.toReadableBuffer("Hello World. ");
        ReadableBuffer content1 = BufferUtil.toReadableBuffer("The quick brown fox jumped over the lazy dog.");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "something");
        fields.add("User-Agent", "test");
        RequestInfo info = new RequestInfo("POST", "/index.html", fields);

        result = gen.generateRequest(info, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, content0, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertTrue(gen.isChunking());
        {
            ReadableBuffer rb = header.toReadable();
            out = BufferUtil.toString(rb);
            rb.toWritable();
        }
        out += BufferUtil.toString(content0);

        result = gen.generateRequest(null, header, null, content1, false);
        assertEquals(HttpGenerator.Result.NEED_CHUNK, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());

        result = gen.generateRequest(null, null, chunk, content1, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertTrue(gen.isChunking());
        {
            ReadableBuffer rb = chunk.toReadable();
            out += BufferUtil.toString(rb);
            rb.toWritable();
        }
        out += BufferUtil.toString(content1);

        result = gen.generateResponse(null, false, null, chunk, null, true);
        assertEquals(HttpGenerator.Result.CONTINUE, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertTrue(gen.isChunking());

        result = gen.generateResponse(null, false, null, chunk, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        {
            ReadableBuffer rb = chunk.toReadable();
            out += BufferUtil.toString(rb);
            rb.toWritable();
        }
        assertFalse(gen.isChunking());

        result = gen.generateResponse(null, false, null, chunk, null, true);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());

        assertThat(out, Matchers.containsString("POST /index.html HTTP/1.1"));
        assertThat(out, Matchers.containsString("Host: something"));
        assertThat(out, Matchers.containsString("Transfer-Encoding: chunked"));
        assertThat(out, Matchers.containsString("\r\nD\r\nHello World. \r\n"));
        assertThat(out, Matchers.containsString("\r\n2D\r\nThe quick brown fox jumped over the lazy dog.\r\n"));
        assertThat(out, Matchers.containsString("\r\n0\r\n\r\n"));

        assertEquals(58, gen.getContentPrepared());
    }

    @Test
    public void testRequestWithKnownContent() throws Exception
    {
        String out;
        WritableBuffer header = WritableBuffer.allocate(4096, false);
        WritableBuffer chunk = WritableBuffer.allocate(HttpGenerator.CHUNK_SIZE, false);
        ReadableBuffer content0 = BufferUtil.toReadableBuffer("Hello World. ");
        ReadableBuffer content1 = BufferUtil.toReadableBuffer("The quick brown fox jumped over the lazy dog.");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "something");
        fields.add("User-Agent", "test");
        RequestInfo info = new RequestInfo("POST", "/index.html", 58, fields);

        result = gen.generateRequest(info, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, content0, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertFalse(gen.isChunking());
        out = BufferUtil.toString(header.toReadable());
        out += BufferUtil.toString(content0);

        result = gen.generateRequest(null, null, null, content1, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertFalse(gen.isChunking());
        out += BufferUtil.toString(content1);

        result = gen.generateResponse(null, false, null, null, null, true);
        assertEquals(HttpGenerator.Result.CONTINUE, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertFalse(gen.isChunking());

        result = gen.generateResponse(null, false, null, null, null, true);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        out += BufferUtil.toString(chunk.toReadable());

        assertThat(out, Matchers.containsString("POST /index.html HTTP/1.1"));
        assertThat(out, Matchers.containsString("Host: something"));
        assertThat(out, Matchers.containsString("Content-Length: 58"));
        assertThat(out, Matchers.containsString("\r\n\r\nHello World. The quick brown fox jumped over the lazy dog."));

        assertEquals(58, gen.getContentPrepared());
    }
}
