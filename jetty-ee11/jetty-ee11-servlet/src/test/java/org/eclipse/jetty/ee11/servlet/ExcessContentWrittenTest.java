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

package org.eclipse.jetty.ee11.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests writing more content than declared by {@link HttpServletResponse#setContentLength(int)}.
 * @see <a href="https://github.com/jetty/jetty.project/issues/15421">Issue #15421</a>
 */
public class ExcessContentWrittenTest
{
    private Server _server;
    private LocalConnector _connector;

    public void start(HttpServlet servlet) throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(servlet, "/*");
        _server.setHandler(context);
        _server.start();
    }

    @AfterEach
    public void stop()
    {
        LifeCycle.stop(_server);
    }

    private HttpTester.Response getResponse() throws Exception
    {
        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");
        request.setHeader("Connection", "close");
        return HttpTester.parseResponse(_connector.getResponse(request.generate()));
    }

    @Test
    public void testWriteMoreThanContentLength() throws Exception
    {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicBoolean closedOnExtraWrite = new AtomicBoolean();
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    response.setContentLength(100);
                    byte[] content = new byte[200];
                    Arrays.fill(content, 0, 100, (byte)'a');
                    Arrays.fill(content, 100, 200, (byte)'b');
                    OutputStream out = response.getOutputStream();
                    out.write(content);
                    try
                    {
                        out.write(new byte[1]);
                    }
                    catch (IOException ignored)
                    {
                        closedOnExtraWrite.set(true);
                    }
                }
                finally
                {
                    complete.countDown();
                }
            }
        });

        HttpTester.Response response = getResponse();
        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertThat(response.getStatus(), is(200));
        assertThat(response.get(HttpHeader.CONTENT_LENGTH), is("100"));
        assertThat(response.getContentBytes().length, is(100));
        assertThat(response.getContent(), is("a".repeat(100)));
        assertTrue(closedOnExtraWrite.get());
    }

    @Test
    public void testWritePartialThenExcess() throws Exception
    {
        CountDownLatch complete = new CountDownLatch(1);
        AtomicBoolean closedOnExtraWrite = new AtomicBoolean();
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    response.setContentLength(100);
                    OutputStream out = response.getOutputStream();
                    out.write(new byte[60]);
                    out.write(new byte[80]); // only 40 of these should be written
                    try
                    {
                        out.write(new byte[10]);
                    }
                    catch (IOException ignored)
                    {
                        closedOnExtraWrite.set(true);
                    }
                }
                finally
                {
                    complete.countDown();
                }
            }
        });

        HttpTester.Response response = getResponse();
        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertThat(response.getStatus(), is(200));
        assertThat(response.get(HttpHeader.CONTENT_LENGTH), is("100"));
        assertThat(response.getContentBytes().length, is(100));
        assertTrue(closedOnExtraWrite.get());
    }

    @Test
    public void testWriteMoreThanContentLengthWithByteBuffer() throws Exception
    {
        CountDownLatch complete = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                try
                {
                    response.setContentLength(50);
                    ByteBuffer buffer = ByteBuffer.allocate(100);
                    for (int i = 0; i < 100; i++)
                        buffer.put((byte)('A' + (i % 26)));
                    buffer.flip();
                    response.getOutputStream().write(buffer);
                    assertThat(buffer.position(), is(50));
                    assertThat(buffer.limit(), is(100));
                }
                finally
                {
                    complete.countDown();
                }
            }
        });

        HttpTester.Response response = getResponse();
        assertTrue(complete.await(5, TimeUnit.SECONDS));
        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentBytes().length, is(50));
        assertThat(response.getContent(), is("ABCDEFGHIJKLMNOPQRSTUVWXYZABCDEFGHIJKLMNOPQRSTUVWX"));
    }
}
