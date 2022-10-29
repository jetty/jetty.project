//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SizeLimitHandlerTest
{
    private Server _server;
    private LocalConnector _local;
    private ContextHandler _contextHandler;

    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();
        HttpConfiguration config = new HttpConfiguration();
        config.setOutputBufferSize(1024);
        _local = new LocalConnector(_server, new HttpConnectionFactory(config));
        _server.setConnectors(new Connector[]{_local});
        SizeLimitHandler sizeLimitHandler = new SizeLimitHandler(8192, 8192);
        _contextHandler = new ContextHandler("/ctx");
        _server.setHandler(sizeLimitHandler);
        sizeLimitHandler.setHandler(_contextHandler);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testSmallGET() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.getOutputStream().println("Hello World");
            }
        });
        _server.start();
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /ctx/hello HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("Hello World"));
    }

    @Test
    public void testLargeGETSingleByteBuffer() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                baseRequest.getResponse().getHttpOutput().sendContent(ByteBuffer.wrap(new byte[8 * 1024 + 1]));
            }
        });
        _server.start();
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /ctx/hello HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(413));
        assertThat(response.getContent(), containsString("8193&gt;8192"));
    }

    @Test
    public void testLargeGETManyWrites() throws Exception
    {
        AtomicBoolean error = new AtomicBoolean();
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                byte[] data = new byte[1024];
                Arrays.fill(data, (byte)'X');
                data[1023] = (byte)'\n';
                String text = new String(data, 0, 1024, Charset.defaultCharset());
                PrintWriter out = response.getWriter();

                try
                {
                    for (int i = 0; i <= 10; i++)
                    {
                        out.println(i);
                        out.write(text);
                        out.flush();
                    }
                }
                catch (BadMessageException e)
                {
                    error.set(true);
                    throw e;
                }
            }
        });
        _server.start();
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /ctx/hello HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertTrue(error.get());

        assertThat(response.getContent(), not(containsString("8")));
        assertThat(response.getContent(), not(containsString("9")));
        assertThat(response.getContent(), not(containsString("10")));
        assertFalse(response.isComplete());
    }

    @Test
    public void testSmallPOST() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                String content = IO.toString(request.getInputStream());
                response.getOutputStream().println("OK " + content.length());
            }
        });
        _server.start();
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("POST /ctx/hello HTTP/1.0\r\n" +
                "Content-Length: 8\r\n" +
                "\r\n" +
                "123456\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("OK 8"));
    }

    @Test
    public void testLargePOSTKnownSize() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                String content = IO.toString(request.getInputStream());
                response.getOutputStream().println("OK " + content.length());
            }
        });
        _server.start();
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("POST /ctx/hello HTTP/1.0\r\n" +
                "Content-Length: 32768\r\n" +
                "\r\n" +
                "123456..."));
        assertThat(response.getStatus(), equalTo(413));
        assertThat(response.getContent(), containsString("32768&gt;8192"));
    }

    @Test
    public void testLargePOSTUnknownSize() throws Exception
    {
        _contextHandler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                String content = IO.toString(request.getInputStream());
                response.getOutputStream().println("OK " + content.length());
            }
        });
        _server.start();

        try (LocalConnector.LocalEndPoint endp = _local.executeRequest(
            "POST /ctx/hello HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n"))
        {
            byte[] data = new byte[1024];
            Arrays.fill(data, (byte)'X');
            data[1023] = (byte)'\n';
            String text = new String(data, 0, 1024, Charset.defaultCharset());

            for (int i = 0; i < 9; i++)
                endp.addInput("400\r\n" + text + "\r\n");

            HttpTester.Response response = HttpTester.parseResponse(endp.getResponse());

            assertThat(response.getStatus(), equalTo(413));
            assertThat(response.getContent(), containsString("&gt;8192"));
        }
    }
}
