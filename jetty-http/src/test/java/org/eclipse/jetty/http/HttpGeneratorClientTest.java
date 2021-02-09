//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpGeneratorClientTest
{
    public static final String[] connect = {null, "keep-alive", "close"};

    class Info extends MetaData.Request
    {
        Info(String method, String uri)
        {
            super(method, new HttpURI(uri), HttpVersion.HTTP_1_1, new HttpFields(), -1);
        }

        public Info(String method, String uri, int contentLength)
        {
            super(method, new HttpURI(uri), HttpVersion.HTTP_1_1, new HttpFields(), contentLength);
        }
    }

    @Test
    public void testGETRequestNoContent() throws Exception
    {
        ByteBuffer header = BufferUtil.allocate(2048);
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        Info info = new Info("GET", "/index.html");
        info.getFields().add("Host", "something");
        info.getFields().add("User-Agent", "test");
        assertTrue(!gen.isChunking());

        result = gen.generateRequest(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertTrue(!gen.isChunking());
        String out = BufferUtil.toString(header);
        BufferUtil.clear(header);

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertTrue(!gen.isChunking());

        assertEquals(0, gen.getContentPrepared());
        assertThat(out, Matchers.containsString("GET /index.html HTTP/1.1"));
        assertThat(out, Matchers.not(Matchers.containsString("Content-Length")));
    }

    @Test
    public void testEmptyHeaders() throws Exception
    {
        ByteBuffer header = BufferUtil.allocate(2048);
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        Info info = new Info("GET", "/index.html");
        info.getFields().add("Host", "something");
        info.getFields().add("Null", null);
        info.getFields().add("Empty", "");
        assertTrue(!gen.isChunking());

        result = gen.generateRequest(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertTrue(!gen.isChunking());
        String out = BufferUtil.toString(header);
        BufferUtil.clear(header);

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertTrue(!gen.isChunking());

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

        Info info = new Info("GET", "/index.html");
        info.getFields().add("Host", "localhost");
        info.getFields().add("Field", "SomeWhatLongValue");
        info.setHttpVersion(HttpVersion.HTTP_1_0);

        HttpGenerator.Result result = gen.generateRequest(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);

        ByteBuffer header = BufferUtil.allocate(16);
        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.HEADER_OVERFLOW, result);

        header = BufferUtil.allocate(2048);
        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertFalse(gen.isChunking());
        String out = BufferUtil.toString(header);
        BufferUtil.clear(header);

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
        ByteBuffer header = BufferUtil.allocate(2048);
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        Info info = new Info("POST", "/index.html");
        info.getFields().add("Host", "something");
        info.getFields().add("User-Agent", "test");
        assertTrue(!gen.isChunking());

        result = gen.generateRequest(info, null, null, null, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertTrue(!gen.isChunking());
        String out = BufferUtil.toString(header);
        BufferUtil.clear(header);

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertTrue(!gen.isChunking());

        assertEquals(0, gen.getContentPrepared());
        assertThat(out, Matchers.containsString("POST /index.html HTTP/1.1"));
        assertThat(out, Matchers.containsString("Content-Length: 0"));
    }

    @Test
    public void testRequestWithContent() throws Exception
    {
        String out;
        ByteBuffer header = BufferUtil.allocate(4096);
        ByteBuffer content0 = BufferUtil.toBuffer("Hello World. The quick brown fox jumped over the lazy dog.");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, content0, true);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        Info info = new Info("POST", "/index.html");
        info.getFields().add("Host", "something");
        info.getFields().add("User-Agent", "test");

        result = gen.generateRequest(info, null, null, content0, true);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, content0, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertTrue(!gen.isChunking());
        out = BufferUtil.toString(header);
        BufferUtil.clear(header);
        out += BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertTrue(!gen.isChunking());

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
        ByteBuffer header = BufferUtil.allocate(4096);
        ByteBuffer chunk = BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
        ByteBuffer content0 = BufferUtil.toBuffer("Hello World. ");
        ByteBuffer content1 = BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog.");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        Info info = new Info("POST", "/index.html");
        info.getFields().add("Host", "something");
        info.getFields().add("User-Agent", "test");

        result = gen.generateRequest(info, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, content0, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertTrue(gen.isChunking());
        out = BufferUtil.toString(header);
        BufferUtil.clear(header);
        out += BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result = gen.generateRequest(null, header, null, content1, false);
        assertEquals(HttpGenerator.Result.NEED_CHUNK, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());

        result = gen.generateRequest(null, null, chunk, content1, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertTrue(gen.isChunking());
        out += BufferUtil.toString(chunk);
        BufferUtil.clear(chunk);
        out += BufferUtil.toString(content1);
        BufferUtil.clear(content1);

        result = gen.generateResponse(null, false, null, chunk, null, true);
        assertEquals(HttpGenerator.Result.CONTINUE, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertTrue(gen.isChunking());

        result = gen.generateResponse(null, false, null, chunk, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        out += BufferUtil.toString(chunk);
        BufferUtil.clear(chunk);
        assertTrue(!gen.isChunking());

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
        ByteBuffer header = BufferUtil.allocate(4096);
        ByteBuffer chunk = BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
        ByteBuffer content0 = BufferUtil.toBuffer("Hello World. ");
        ByteBuffer content1 = BufferUtil.toBuffer("The quick brown fox jumped over the lazy dog.");
        HttpGenerator gen = new HttpGenerator();

        HttpGenerator.Result
            result = gen.generateRequest(null, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_INFO, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        Info info = new Info("POST", "/index.html", 58);
        info.getFields().add("Host", "something");
        info.getFields().add("User-Agent", "test");

        result = gen.generateRequest(info, null, null, content0, false);
        assertEquals(HttpGenerator.Result.NEED_HEADER, result);
        assertEquals(HttpGenerator.State.START, gen.getState());

        result = gen.generateRequest(info, header, null, content0, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertTrue(!gen.isChunking());
        out = BufferUtil.toString(header);
        BufferUtil.clear(header);
        out += BufferUtil.toString(content0);
        BufferUtil.clear(content0);

        result = gen.generateRequest(null, null, null, content1, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertTrue(!gen.isChunking());
        out += BufferUtil.toString(content1);
        BufferUtil.clear(content1);

        result = gen.generateResponse(null, false, null, null, null, true);
        assertEquals(HttpGenerator.Result.CONTINUE, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertTrue(!gen.isChunking());

        result = gen.generateResponse(null, false, null, null, null, true);
        assertEquals(HttpGenerator.Result.DONE, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        out += BufferUtil.toString(chunk);
        BufferUtil.clear(chunk);

        assertThat(out, Matchers.containsString("POST /index.html HTTP/1.1"));
        assertThat(out, Matchers.containsString("Host: something"));
        assertThat(out, Matchers.containsString("Content-Length: 58"));
        assertThat(out, Matchers.containsString("\r\n\r\nHello World. The quick brown fox jumped over the lazy dog."));

        assertEquals(58, gen.getContentPrepared());
    }
}
