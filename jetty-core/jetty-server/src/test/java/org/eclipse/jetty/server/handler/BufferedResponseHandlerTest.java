//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Arrays;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.NoopByteBufferPool;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class BufferedResponseHandlerTest
{
    private Server _server;
    private LocalConnector _local;
    private TestHandler _test;

    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();
        _server.addBean(new NoopByteBufferPool()); // Avoid giving larger buffers than requested
        HttpConfiguration config = new HttpConfiguration();
        config.setOutputBufferSize(1024);
        config.setOutputAggregationSize(256);
        _local = new LocalConnector(_server, new HttpConnectionFactory(config));
        _server.addConnector(_local);

        BufferedResponseHandler bufferedHandler = new BufferedResponseHandler();
        bufferedHandler.getPathIncludeExclude().include("/include/*");
        bufferedHandler.getPathIncludeExclude().exclude("*.exclude");
        bufferedHandler.getMimeIncludeExclude().exclude("text/excluded");
        bufferedHandler.setHandler(_test = new TestHandler());


        ContextHandler contextHandler = new ContextHandler("/ctx");
        contextHandler.setHandler(bufferedHandler);

        _server.setHandler(contextHandler);
        _server.start();

        // BufferedResponseHandler.LOG.setDebugEnabled(true);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testNormal() throws Exception
    {
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
        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, containsString("Written: true"));
    }

    @Test
    public void testExcludedByPath() throws Exception
    {
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
        _test._mimeType = "text/excluded";
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
        _test._flush = true;
        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, containsString("Written: true"));
    }

    @Test
    public void testBufferSizeSmall() throws Exception
    {
        _test._bufferSize = 16;
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
        _test._bufferSize = 4096;
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
        _test._writes = 1;
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
        _test._writes = 1;
        _test._flush = true;
        _test._content = new byte[0];
        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Content-Length: "));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, not(containsString("Write: 1")));
        assertThat(response, containsString("Written: true"));
    }

    @Test
    public void testReset() throws Exception
    {
        _test._reset = true;
        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, containsString("Written: true"));
        assertThat(response, not(containsString("RESET")));
    }

    public static class TestHandler extends Handler.Abstract
    {
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
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            response.setStatus(200);

            if (_bufferSize > 0)
                request.setAttribute(BufferedResponseHandler.BUFFER_SIZE_ATTRIBUTE_NAME, _bufferSize);
            if (_mimeType != null)
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, _mimeType);

            // Do not close the stream before adding the header: Written: true.
            OutputStream outputStream = Content.Sink.asOutputStream(response);
            for (int i = 0; i < _writes; i++)
            {
                response.getHeaders().add("Write", Integer.toString(i));
                outputStream.write(_content);
                if (_flush)
                    outputStream.flush();
            }

            response.getHeaders().add("Written", "true");
            callback.succeeded();
            return true;
        }
    }
}
