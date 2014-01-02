//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpParser;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class AsyncIOServletTest
{
    private Server server;
    private ServerConnector connector;
    private ServletContextHandler context;
    private String path = "/path";

    public void startServer(HttpServlet servlet) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        context = new ServletContextHandler(server, "", false, false);
        ServletHolder holder = new ServletHolder(servlet);
        holder.setAsyncSupported(true);
        context.addServlet(holder, path);

        server.start();
    }

    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testAsyncReadThrowsException() throws Exception
    {
        testAsyncReadThrows(new NullPointerException("explicitly_thrown_by_test"));
    }

    @Test
    public void testAsyncReadThrowsError() throws Exception
    {
        testAsyncReadThrows(new Error("explicitly_thrown_by_test"));
    }

    private void testAsyncReadThrows(final Throwable throwable) throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                final AsyncContext asyncContext = request.startAsync(request, response);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        if (throwable instanceof RuntimeException)
                            throw (RuntimeException)throwable;
                        if (throwable instanceof Error)
                            throw (Error)throwable;
                        throw new IOException(throwable);
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        Assert.assertSame(throwable, t);
                        latch.countDown();
                        response.setStatus(500);
                        asyncContext.complete();
                    }
                });
            }
        });

        String data = "0123456789";
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Length: " + data.length() + "\r\n" +
                "\r\n" +
                data;

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8")));

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            Assert.assertEquals("500", response.getCode());
        }
    }

    @Test
    public void testOnErrorThrows() throws Exception
    {
        final AtomicInteger errors = new AtomicInteger();
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                final AsyncContext asyncContext = request.startAsync(request, response);
                request.getInputStream().setReadListener(new ReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        throw new NullPointerException("explicitly_thrown_by_test_1");
                    }

                    @Override
                    public void onAllDataRead() throws IOException
                    {
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        errors.incrementAndGet();
                        throw new NullPointerException("explicitly_thrown_by_test_2");
                    }
                });
            }
        });

        String data = "0123456789";
        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "Content-Length: " + data.length() + "\r\n" +
                "\r\n" +
                data;

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8")));

            Assert.assertEquals("500", response.getCode());
            Assert.assertEquals(1, errors.get());
        }
    }

    @Test
    public void testAsyncWriteThrowsException() throws Exception
    {
        testAsyncWriteThrows(new NullPointerException("explicitly_thrown_by_test"));
    }

    @Test
    public void testAsyncWriteThrowsError() throws Exception
    {
        testAsyncWriteThrows(new Error("explicitly_thrown_by_test"));
    }

    private void testAsyncWriteThrows(final Throwable throwable) throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(1);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                final AsyncContext asyncContext = request.startAsync(request, response);
                response.getOutputStream().setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        if (throwable instanceof RuntimeException)
                            throw (RuntimeException)throwable;
                        if (throwable instanceof Error)
                            throw (Error)throwable;
                        throw new IOException(throwable);
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        Assert.assertSame(throwable, t);
                        latch.countDown();
                        response.setStatus(500);
                        asyncContext.complete();
                    }
                });
            }
        });

        String request = "GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n";

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(request.getBytes("UTF-8"));
            output.flush();

            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8")));

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
            Assert.assertEquals("500", response.getCode());
        }
    }
}
