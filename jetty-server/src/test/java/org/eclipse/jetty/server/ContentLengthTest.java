//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpParser;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.eclipse.jetty.util.IO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class ContentLengthTest
{
    private Server server;
    private ServerConnector connector;

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testDuplicateContentLengthWithLargerAndCorrectValue() throws Exception
    {
        String content = "hello_world";
        testDuplicateContentLength(content, 2 * content.length(), content.length());
    }

    @Test
    public void testDuplicateContentLengthWithCorrectAndLargerValue() throws Exception
    {
        String content = "hello_world";
        testDuplicateContentLength(content, content.length(), 2 * content.length());
    }

    private void testDuplicateContentLength(String content, long length1, long length2) throws Exception
    {
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            String request = "" +
                    "POST / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: " + length1 + "\r\n" +
                    "Content-Length: " + length2 + "\r\n" +
                    "\r\n" +
                    content;
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            SimpleHttpParser parser = new SimpleHttpParser();
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            SimpleHttpResponse response = parser.readResponse(reader);

            Assert.assertEquals(HttpStatus.BAD_REQUEST_400, Integer.parseInt(response.getCode()));
        }
    }

    @Test
    public void testTransferEncodingChunkedBeforeContentLength() throws Exception
    {
        String content = "hello_world";
        testTransferEncodingChunkedAndContentLength(content, "Transfer-Encoding: chunked", "Content-Length: " + content.length());
    }

    @Test
    public void testContentLengthBeforeTransferEncodingChunked() throws Exception
    {
        String content = "hello_world";
        testTransferEncodingChunkedAndContentLength(content, "Content-Length: " + content.length(), "Transfer-Encoding: chunked");
    }

    private void testTransferEncodingChunkedAndContentLength(String content, String header1, String header2) throws Exception
    {
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                String body = IO.toString(request.getInputStream());
                Assert.assertEquals(content, body);
            }
        });

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            String request = "" +
                    "POST / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    header1 + "\r\n" +
                    header2 + "\r\n" +
                    "\r\n" +
                    Integer.toHexString(content.length()) + "\r\n" +
                    content +
                    "0\r\n" +
                    "\r\n";
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            SimpleHttpParser parser = new SimpleHttpParser();
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            SimpleHttpResponse response = parser.readResponse(reader);

            Assert.assertEquals(HttpStatus.OK_200, Integer.parseInt(response.getCode()));
        }
    }
}
