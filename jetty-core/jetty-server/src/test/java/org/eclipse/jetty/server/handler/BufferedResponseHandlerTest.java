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

package org.eclipse.jetty.server.handler;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class BufferedResponseHandlerTest
{
    private Server _server;
    private LocalConnector _local;

    public void startServer(Consumer<BufferedResponseHandler> bufferedResponseHandlerConsumer) throws Exception
    {
        _server = new Server();
        HttpConfiguration config = new HttpConfiguration();
        config.setOutputBufferSize(1024);
        config.setOutputAggregationSize(256);
        _local = new LocalConnector(_server, new HttpConnectionFactory(config));
        _server.addConnector(_local);

        BufferedResponseHandler bufferedHandler = new BufferedResponseHandler();
        bufferedResponseHandlerConsumer.accept(bufferedHandler);

        ContextHandler contextHandler = new ContextHandler("/ctx");
        contextHandler.setHandler(bufferedHandler);

        _server.setHandler(contextHandler);
        _server.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNormal() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            bufferedResponseHandler.setHandler(new TestHandler());
        });

        String response = _local.getResponse("GET /ctx/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, not(containsString("Content-Length: ")));
        assertThat(response, not(containsString("Write: 1")));
        assertThat(response, not(containsString("Write: 9")));
        assertThat(response, not(containsString("Written: true")));
    }

    @Test
    public void testIncluded() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            testHandler._bufferSize = 2048;
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, containsString("Written: true"));
        assertThat(response, containsString("Content-Length: "));
    }

    @Test
    public void testExcludedByPath() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path.exclude HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, not(containsString("Content-Length: ")));
        assertThat(response, not(containsString("Write: 1")));
        assertThat(response, not(containsString("Write: 9")));
        assertThat(response, not(containsString("Written: true")));
    }

    @Test
    public void testExcludedByMime() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            testHandler._mimeType = "text/excluded";
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, not(containsString("Content-Length: ")));
        assertThat(response, not(containsString("Write: 1")));
        assertThat(response, not(containsString("Write: 9")));
        assertThat(response, not(containsString("Written: true")));
    }

    @Test
    public void testFlushed() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            testHandler._writes = 4;
            testHandler._flush = true;
            testHandler._bufferSize = 2048;
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 1"));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, not(containsString("Write: 3")));
        assertThat(response, not(containsString("Written: true")));
    }

    @Test
    public void testBufferSizeSmall() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            testHandler._aggregationSize = 16;
            testHandler._bufferSize = 16;
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, not(containsString("Content-Length: ")));
        assertThat(response, not(containsString("Write: 1")));
        assertThat(response, not(containsString("Write: 9")));
        assertThat(response, not(containsString("Written: true")));
    }

    @Test
    public void testBufferSizeBig() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            testHandler._bufferSize = 4096;
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Content-Length: "));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, containsString("Written: true"));
    }

    @Test
    public void testOne() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            testHandler._writes = 1;
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Content-Length: "));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, not(containsString("Write: 1")));
        assertThat(response, containsString("Written: true"));
    }

    @Test
    public void testFlushEmpty() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            testHandler._writes = 1;
            testHandler._flush = true;
            testHandler._content = new byte[0];
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, not(containsString("Write: 1")));
        assertThat(response, not(containsString("Written: true")));
    }

    @Test
    public void testFlushEmptyWriteSomeClose() throws Exception
    {
        final String content = "X".repeat(128) + "\n";

        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            Handler.Abstract handler = new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception
                {
                    try (OutputStream outputStream = Content.Sink.asOutputStream(response))
                    {
                        // Flush empty first.
                        outputStream.flush();
                        // Then write
                        outputStream.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                    callback.succeeded();
                    return true;
                }
            };
            bufferedResponseHandler.setHandler(handler);
        });

        String rawResponse = _local.getResponse("""
            GET /ctx/include/path HTTP/1.1
            Host: localhost
            
            """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertEquals(200, response.getStatus());
        assertThat(response.get(HttpHeader.TRANSFER_ENCODING), containsString("chunked"));
        assertThat(response.getContent(), is(content));
    }

    @Test
    public void testReset() throws Exception
    {
        startServer(bufferedResponseHandler ->
        {
            bufferedResponseHandler.includePath("/include/*");
            bufferedResponseHandler.excludePath("*.exclude");
            bufferedResponseHandler.excludeMimeType("text/excluded");
            TestHandler testHandler = new TestHandler();
            testHandler._reset = true;
            testHandler._bufferSize = 2048;
            bufferedResponseHandler.setHandler(testHandler);
        });

        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, containsString("Written: true"));
        assertThat(response, not(containsString("RESET")));
    }

    public static class TestHandler extends Handler.Abstract
    {
        int _aggregationSize = -1;
        int _bufferSize = -1;
        String _mimeType;
        byte[] _content = new byte[128];
        int _writes = 10;
        boolean _flush;
        boolean _reset;

        public TestHandler()
        {
            Arrays.fill(_content, (byte)'X');
            _content[_content.length - 1] = '\n';
        }

        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);

            if (_bufferSize > 0)
                request.setAttribute(BufferedResponseHandler.BUFFER_SIZE_ATTRIBUTE_NAME, _bufferSize);
            if (_aggregationSize > 0)
                request.setAttribute(BufferedResponseHandler.MAX_AGGREGATION_SIZE_ATTRIBUTE_NAME, _aggregationSize);
            if (_mimeType != null)
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, _mimeType);

            // Do not close the stream before adding the header: Written: true.
            try (OutputStream outputStream = Content.Sink.asOutputStream(response))
            {
                for (int i = 0; i < _writes; i++)
                {
                    response.getHeaders().add("Write", Integer.toString(i));
                    outputStream.write(_content);
                    if (_flush && i % 2 == 1)
                        outputStream.flush();
                }
                if (_flush)
                    outputStream.flush();
                response.getHeaders().add("Written", "true");
            }
            callback.succeeded();

            return true;
        }
    }
}
