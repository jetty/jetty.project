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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

/**
 * Resource Handler test
 */
public class FileBufferedResponseHandlerTest
{
    private static Server _server;
    private static LocalConnector _local;
    private static TestHandler _test;
    private static Path _testDir;

    @BeforeAll
    public static void setUp() throws Exception
    {
        _testDir = MavenTestingUtils.getTargetTestingPath(FileBufferedResponseHandlerTest.class.getName());
        FS.ensureDirExists(_testDir);

        _server = new Server();
        HttpConfiguration config = new HttpConfiguration();
        config.setOutputBufferSize(1024);
        config.setOutputAggregationSize(256);
        _local = new LocalConnector(_server, new HttpConnectionFactory(config));
        _server.addConnector(_local);

        FileBufferedResponseHandler bufferedHandler = new FileBufferedResponseHandler();
        bufferedHandler.setTempDir(_testDir);
        bufferedHandler.getPathIncludeExclude().include("/include/*");
        bufferedHandler.getPathIncludeExclude().exclude("*.exclude");
        bufferedHandler.getMimeIncludeExclude().exclude("text/excluded");
        bufferedHandler.setHandler(_test = new TestHandler());

        ContextHandler contextHandler = new ContextHandler("/ctx");
        contextHandler.setHandler(bufferedHandler);

        _server.setHandler(contextHandler);
        _server.start();
    }

    @AfterAll
    public static void tearDown() throws Exception
    {
        _server.stop();
    }

    @BeforeEach
    public void before()
    {
        FS.ensureEmpty(_testDir);
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

    public static int getNumFiles()
    {
        File[] files = _testDir.toFile().listFiles();
        if (files == null)
            return 0;

        return files.length;
    }

    @Test
    public void testPathNotIncluded() throws Exception
    {
        String response = _local.getResponse("GET /ctx/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 7"));
        assertThat(response, not(containsString("Content-Length: ")));
        assertThat(response, not(containsString("Write: 8")));
        assertThat(response, not(containsString("Write: 9")));
        assertThat(response, not(containsString("Written: true")));
        assertThat(response, not(containsString("NumFiles:")));
        assertThat(getNumFiles(), is(0));
    }

    @Test
    public void testIncluded() throws Exception
    {
        String response = _local.getResponse("GET /ctx/include/path HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("Write: 0"));
        assertThat(response, containsString("Write: 9"));
        assertThat(response, containsString("Written: true"));
        assertThat(response, containsString("NumFiles: 1"));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, not(containsString("NumFiles:")));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, not(containsString("NumFiles:")));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, containsString("NumFiles: 1"));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, containsString("NumFiles: 1"));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, containsString("NumFiles: 1"));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, containsString("NumFiles: 0"));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, containsString("NumFiles: 0"));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, containsString("NumFiles: 1"));
        assertThat(getNumFiles(), is(0));
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
        assertThat(response, containsString("NumFiles: 1"));
        assertThat(getNumFiles(), is(0));
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

            response.addHeader("NumFiles", Integer.toString(getNumFiles()));

            if (_close)
                response.getOutputStream().close();

            response.addHeader("Written", "true");
        }
    }
}
