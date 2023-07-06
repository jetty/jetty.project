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
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.SizeLimitHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
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
import static org.junit.jupiter.api.Assertions.fail;

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
        _contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.write(true, BufferUtil.toBuffer("Hello World"), callback);
                return true;
            }
        });
        _server.start();
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /ctx/hello HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(200));
        assertThat(response.getContent(), containsString("Hello World"));
    }

    @Test
    public void testLargeGETContentLengthKnown() throws Exception
    {
        _contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                response.getHeaders().put(HttpHeader.CONTENT_LENGTH, 8 * 1024 + 1);
                fail();

                return true;
            }
        });
        _server.start();
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /ctx/hello HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(500));
        assertThat(response.getContent(), containsString("8193&gt;8192"));
    }

    @Test
    public void testLargeGETSingleByteBuffer() throws Exception
    {
        _contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {

                response.write(true, ByteBuffer.wrap(new byte[8 * 1024 + 1]), callback);
                return true;
            }
        });
        _server.start();
        HttpTester.Response response = HttpTester.parseResponse(
            _local.getResponse("GET /ctx/hello HTTP/1.0\r\n\r\n"));
        assertThat(response.getStatus(), equalTo(500));
        assertThat(response.getContent(), containsString("8193&gt;8192"));
    }

    @Test
    public void testLargeGETManyWrites() throws Exception
    {
        AtomicBoolean error = new AtomicBoolean();
        _contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                byte[] data = new byte[1024];
                Arrays.fill(data, (byte)'X');
                data[1023] = (byte)'\n';
                String text = new String(data, 0, 1024, Charset.defaultCharset());
                OutputStream outputStream = Content.Sink.asOutputStream(response);
                PrintWriter out = new PrintWriter(outputStream);

                try
                {
                    for (int i = 0; i <= 10; i++)
                    {
                        out.println(i);
                        out.write(text);
                        out.flush();
                    }
                    out.close();
                    callback.succeeded();
                }
                catch (HttpException.RuntimeException e)
                {
                    error.set(true);
                    throw e;
                }
                return true;
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
        _contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String content =  IO.toString(Content.Source.asInputStream(request));
                PrintWriter out = new PrintWriter(Content.Sink.asOutputStream(response));
                out.println("OK " + content.length());
                out.close();
                callback.succeeded();
                return true;
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
        _contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String content =  IO.toString(Content.Source.asInputStream(request));
                PrintWriter out = new PrintWriter(Content.Sink.asOutputStream(response));
                out.println("OK " + content.length());
                return true;
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
        _contextHandler.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                String content =  IO.toString(Content.Source.asInputStream(request));
                PrintWriter out = new PrintWriter(Content.Sink.asOutputStream(response));
                out.println("OK " + content.length());
                return true;
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
