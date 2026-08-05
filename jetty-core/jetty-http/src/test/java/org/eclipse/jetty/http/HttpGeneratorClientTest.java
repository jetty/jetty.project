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
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
        RetainableByteBuffer.Mutable header = RetainableByteBuffer.Mutable.allocate(2048, false);
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
        String out = BufferUtil.toString(header);

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
        RetainableByteBuffer.Mutable header = RetainableByteBuffer.Mutable.allocate(2048, false);
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
        String out = BufferUtil.toString(header);

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

        RetainableByteBuffer.Mutable header = RetainableByteBuffer.Mutable.allocate(16, false);
        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.HEADER_OVERFLOW, result);

        header = RetainableByteBuffer.Mutable.allocate(2048, false);
        result = gen.generateRequest(info, header, null, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertFalse(gen.isChunking());
        String out = BufferUtil.toString(header);

        result = gen.generateResponse(null, false, null, null, null, false);
        assertEquals(HttpGenerator.Result.SHUTDOWN_OUT, result);
        assertEquals(HttpGenerator.State.END, gen.getState());
        assertFalse(gen.isChunking());

        assertEquals(0, gen.getContentPrepared());
        assertThat(out, Matchers.containsString("GET /index.html HTTP/1.0"));
        assertThat(out, Matchers.not(Matchers.containsString("Content-Length")));
        assertThat(out, Matchers.containsString("Field: SomeWhatLongValue"));
    }

    public static Stream<Arguments> headerOverflowPersistence()
    {
        return Stream.of(HttpVersion.HTTP_1_0, HttpVersion.HTTP_1_1)
            .flatMap(version -> Stream.of(connect)
                .flatMap(connection -> Stream.of("none", "known", "chunked")
                    // HTTP/1.0 cannot frame content of unknown length.
                    .filter(body -> version == HttpVersion.HTTP_1_1 || !"chunked".equals(body))
                    .map(body -> Arguments.of(version, connection, body))));
    }

    @ParameterizedTest
    @MethodSource("headerOverflowPersistence")
    public void testHeaderOverflowPreservesPersistence(HttpVersion version, String connection, String body) throws Exception
    {
        // Generate without overflow, to get the reference output.
        Generated expected = generate(version, connection, body, 4096);
        boolean persistent = version == HttpVersion.HTTP_1_1 ? !"close".equals(connection) : "keep-alive".equals(connection);
        assertEquals(persistent ? HttpGenerator.Result.DONE : HttpGenerator.Result.SHUTDOWN_OUT, expected.result());
        assertThat(expected.out(), Matchers.containsString(" /index.html " + version));

        boolean chunked = "chunked".equals(body);
        assertEquals(chunked, expected.out().contains("Transfer-Encoding: chunked"));

        // Overflow in the request line, at the very end of the header,
        // after the persistence has already been computed, and for
        // chunked content in the chunk line written in the header buffer.
        int headerLength = expected.out().indexOf("\r\n\r\n") + 4;
        int[] headerSizes = chunked ? new int[]{16, headerLength - 1, headerLength + 1} : new int[]{16, headerLength - 1};
        for (int headerSize : headerSizes)
        {
            Generated actual = generate(version, connection, body, headerSize);
            assertTrue(actual.overflowed());
            assertEquals(expected.out(), actual.out());
            assertEquals(expected.result(), actual.result());
        }
    }

    private record Generated(String out, HttpGenerator.Result result, boolean overflowed)
    {
    }

    private Generated generate(HttpVersion version, String connection, String body, int headerSize) throws Exception
    {
        HttpFields.Mutable fields = HttpFields.build();
        fields.add("Host", "localhost");
        fields.add("X-Padding", "X".repeat(64));
        if (connection != null)
            fields.add("Connection", connection);
        ByteBuffer content = null;
        long contentLength = -1;
        switch (body)
        {
            case "known" ->
            {
                content = BufferUtil.toBuffer("0123456789");
                contentLength = 10;
                fields.add("Content-Length", "10");
            }
            case "chunked" -> content = BufferUtil.toBuffer("0123456789");
            default ->
            {
            }
        }
        String method = content == null ? "GET" : "POST";
        MetaData.Request info = new MetaData.Request(method, HttpURI.from(method, "/index.html"), version, fields, contentLength);

        // Mimic HttpSenderOverHTTP, which retries on overflow without resetting
        // the generator, and sends chunked content after the headers with last=false.
        boolean last = !"chunked".equals(body);
        HttpGenerator gen = new HttpGenerator();
        ByteBuffer header = BufferUtil.allocate(headerSize);
        ByteBuffer chunk = null;
        boolean overflowed = false;
        StringBuilder out = new StringBuilder();
        while (true)
        {
            HttpGenerator.Result result = gen.generateRequest(info, header, chunk, content, last);
            switch (result)
            {
                case HEADER_OVERFLOW ->
                {
                    overflowed = true;
                    header = BufferUtil.allocate(4096);
                }
                case NEED_CHUNK -> chunk = BufferUtil.allocate(HttpGenerator.CHUNK_SIZE);
                case NEED_CHUNK_TRAILER -> chunk = BufferUtil.allocate(4096);
                case FLUSH ->
                {
                    for (ByteBuffer buffer : new ByteBuffer[]{header, chunk, content})
                    {
                        if (buffer != null)
                        {
                            out.append(BufferUtil.toString(buffer));
                            BufferUtil.clear(buffer);
                        }
                    }
                    last = true;
                }
                case CONTINUE ->
                {
                }
                case DONE, SHUTDOWN_OUT ->
                {
                    return new Generated(out.toString(), result, overflowed);
                }
                default -> throw new IllegalStateException(result.toString());
            }
        }
    }

    @Test
    public void testPOSTRequestNoContent() throws Exception
    {
        RetainableByteBuffer.Mutable header = RetainableByteBuffer.Mutable.allocate(2048, false);
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
        String out = BufferUtil.toString(header);

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
        RetainableByteBuffer.Mutable header = RetainableByteBuffer.Mutable.allocate(4096, false);
        RetainableByteBuffer content0 = BufferUtil.toReadableBuffer("Hello World. The quick brown fox jumped over the lazy dog.");
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
        out = BufferUtil.toString(header);
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
        RetainableByteBuffer.Mutable header = RetainableByteBuffer.Mutable.allocate(4096, false);
        RetainableByteBuffer.Mutable chunk = RetainableByteBuffer.Mutable.allocate(HttpGenerator.CHUNK_SIZE, false);
        RetainableByteBuffer content0 = BufferUtil.toReadableBuffer("Hello World. ");
        RetainableByteBuffer content1 = BufferUtil.toReadableBuffer("The quick brown fox jumped over the lazy dog.");
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
        out = BufferUtil.toString(header);
        out += BufferUtil.toString(content0);

        result = gen.generateRequest(null, header, null, content1, false);
        assertEquals(HttpGenerator.Result.NEED_CHUNK, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());

        result = gen.generateRequest(null, null, chunk, content1, false);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMMITTED, gen.getState());
        assertTrue(gen.isChunking());
        out += BufferUtil.toString(chunk);
        out += BufferUtil.toString(content1);

        result = gen.generateResponse(null, false, null, chunk, null, true);
        assertEquals(HttpGenerator.Result.CONTINUE, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        assertTrue(gen.isChunking());

        result = gen.generateResponse(null, false, null, chunk, null, true);
        assertEquals(HttpGenerator.Result.FLUSH, result);
        assertEquals(HttpGenerator.State.COMPLETING, gen.getState());
        out += BufferUtil.toString(chunk);
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
        RetainableByteBuffer.Mutable header = RetainableByteBuffer.Mutable.allocate(4096, false);
        RetainableByteBuffer.Mutable chunk = RetainableByteBuffer.Mutable.allocate(HttpGenerator.CHUNK_SIZE, false);
        RetainableByteBuffer content0 = BufferUtil.toReadableBuffer("Hello World. ");
        RetainableByteBuffer content1 = BufferUtil.toReadableBuffer("The quick brown fox jumped over the lazy dog.");
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
        out = BufferUtil.toString(header);
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
        out += BufferUtil.toString(chunk);

        assertThat(out, Matchers.containsString("POST /index.html HTTP/1.1"));
        assertThat(out, Matchers.containsString("Host: something"));
        assertThat(out, Matchers.containsString("Content-Length: 58"));
        assertThat(out, Matchers.containsString("\r\n\r\nHello World. The quick brown fox jumped over the lazy dog."));

        assertEquals(58, gen.getContentPrepared());
    }
}
