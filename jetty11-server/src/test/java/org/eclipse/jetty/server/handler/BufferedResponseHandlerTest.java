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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.util.Arrays;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Resource Handler test
 */
public class BufferedResponseHandlerTest
{
    private static Server _server;
    private static HttpConfiguration _config;
    private static LocalConnector _local;
    private static ContextHandler _contextHandler;
    private static BufferedResponseHandler _bufferedHandler;
    private static TestHandler _test;

    @BeforeAll
    public static void setUp() throws Exception
    {
        _server = new Server();
        _config = new HttpConfiguration();
        _config.setOutputBufferSize(1024);
        _config.setOutputAggregationSize(256);
        _local = new LocalConnector(_server, new HttpConnectionFactory(_config));
        _server.addConnector(_local);

        _bufferedHandler = new BufferedResponseHandler();
        _bufferedHandler.getPathIncludeExclude().include("/include/*");
        _bufferedHandler.getPathIncludeExclude().exclude("*.exclude");
        _bufferedHandler.getMimeIncludeExclude().exclude("text/excluded");
        _bufferedHandler.setHandler(_test = new TestHandler());

        _contextHandler = new ContextHandler("/ctx");
        _contextHandler.setHandler(_bufferedHandler);

        _server.setHandler(_contextHandler);
        _server.start();

        // BufferedResponseHandler.LOG.setDebugEnabled(true);
    }

    @AfterAll
    public static void tearDown() throws Exception
    {
        _server.stop();
    }

    @BeforeEach
    public void before()
    {
        _test._bufferSize = -1;
        _test._mimeType = null;
        _test._content = new byte[128];
        Arrays.fill(_test._content, (byte)'X');
        _test._content[_test._content.length - 1] = '\n';
        _test._writes = 10;
        _test._flush = false;
        _test._close = false;
        _test._reset = false;
    }

    @Test
    public void testNormal() throws Exception
    {
        String response = _local.getResponse("GET /ctx/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 7"));
        assertThat(response, not(containsString("Content-Length: ")));
        assertThat(response, not(containsString("Write: 8")));
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
        assertThat(response, containsString("Write: 7"));
        assertThat(response, not(containsString("Content-Length: ")));
        assertThat(response, not(containsString("Write: 8")));
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
        assertThat(response, containsString("Write: 7"));
        assertThat(response, not(containsString("Content-Length: ")));
        assertThat(response, not(containsString("Write: 8")));
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
    public void testClosed() throws Exception
    {
        _test._close = true;
        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, not(containsString("Written: true")));
    }

    @Test
    public void testBufferSizeSmall() throws Exception
    {
        _test._bufferSize = 16;
        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, containsString("Written: true"));
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
        _test._close = false;
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

    public static class TestHandler extends AbstractHandler
    {
        int _bufferSize;
        String _mimeType;
        byte[] _content;
        int _writes;
        boolean _flush;
        boolean _close;
        boolean _reset;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);

            if (_bufferSize > 0)
                response.setBufferSize(_bufferSize);
            if (_mimeType != null)
                response.setContentType(_mimeType);

            if (_reset)
            {
                response.getOutputStream().print("THIS WILL BE RESET");
                response.getOutputStream().flush();
                response.getOutputStream().print("THIS WILL BE RESET");
                response.resetBuffer();
            }
            for (int i = 0; i < _writes; i++)
            {
                response.addHeader("Write", Integer.toString(i));
                response.getOutputStream().write(_content);
                if (_flush)
                    response.getOutputStream().flush();
            }

            if (_close)
                response.getOutputStream().close();
            response.addHeader("Written", "true");
        }
    }
}
