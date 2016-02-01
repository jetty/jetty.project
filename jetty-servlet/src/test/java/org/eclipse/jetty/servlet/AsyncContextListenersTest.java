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

package org.eclipse.jetty.servlet;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.ServletException;
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

public class AsyncContextListenersTest
{
    private Server _server;
    private ServerConnector _connector;

    public void prepare(String path, HttpServlet servlet) throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler context = new ServletContextHandler(_server, "/", false, false);
        context.addServlet(new ServletHolder(servlet), path);

        _server.start();
    }

    @After
    public void dispose() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testListenerClearedOnSecondRequest() throws Exception
    {
        final AtomicReference<CountDownLatch> completes = new AtomicReference<>(new CountDownLatch(1));
        String path = "/path";
        prepare(path, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync(request, response);
                asyncContext.addListener(new AsyncListener()
                {
                    @Override
                    public void onStartAsync(AsyncEvent event) throws IOException
                    {
                    }

                    @Override
                    public void onComplete(AsyncEvent event) throws IOException
                    {
                        completes.get().countDown();
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                    }
                });
                asyncContext.complete();
            }
        });

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            OutputStream output = socket.getOutputStream();

            String request = "" +
                    "GET " + path + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(reader);
            Assert.assertEquals("200", response.getCode());
            completes.get().await(10,TimeUnit.SECONDS);

            // Send a second request
            completes.set(new CountDownLatch(1));
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = parser.readResponse(reader);
            Assert.assertEquals("200", response.getCode());
            completes.get().await(10,TimeUnit.SECONDS);
        }
    }

    @Test
    public void testListenerAddedFromListener() throws Exception
    {
        final AtomicReference<CountDownLatch> completes = new AtomicReference<>(new CountDownLatch(1));
        String path = "/path";
        prepare(path, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync(request, response);
                asyncContext.addListener(new AsyncListener()
                {
                    @Override
                    public void onStartAsync(AsyncEvent event) throws IOException
                    {
                        // This method should not be invoked because we add the
                        // listener *after* having called startAsync(), but we
                        // add a listener to be sure it's not called (it will
                        // screw up the completes count and test will fail).
                        event.getAsyncContext().addListener(this);
                    }

                    @Override
                    public void onComplete(AsyncEvent event) throws IOException
                    {
                        completes.get().countDown();
                    }

                    @Override
                    public void onTimeout(AsyncEvent event) throws IOException
                    {
                    }

                    @Override
                    public void onError(AsyncEvent event) throws IOException
                    {
                    }
                });
                asyncContext.complete();
            }
        });

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            OutputStream output = socket.getOutputStream();

            String request = "" +
                    "GET " + path + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(reader);
            Assert.assertEquals("200", response.getCode());
            completes.get().await(10,TimeUnit.SECONDS);

            // Send a second request
            completes.set(new CountDownLatch(1));
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = parser.readResponse(reader);
            Assert.assertEquals("200", response.getCode());
            completes.get().await(10,TimeUnit.SECONDS);
        }
    }
    @Test
    public void testAsyncDispatchAsyncCompletePreservesListener() throws Exception
    {
        final AtomicReference<CountDownLatch> completes = new AtomicReference<>(new CountDownLatch(1));
        final String path = "/path";
        prepare(path + "/*", new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                String requestURI = request.getRequestURI();
                if (requestURI.endsWith("/one"))
                {
                    AsyncContext asyncContext = request.startAsync(request, response);
                    asyncContext.addListener(new AsyncListener()
                    {
                        @Override
                        public void onStartAsync(AsyncEvent event) throws IOException
                        {
                            event.getAsyncContext().addListener(this);
                        }

                        @Override
                        public void onComplete(AsyncEvent event) throws IOException
                        {
                            completes.get().countDown();
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) throws IOException
                        {
                        }

                        @Override
                        public void onError(AsyncEvent event) throws IOException
                        {
                        }
                    });
                    // This dispatch() will call startAsync() again which will
                    // clear the previous listeners (as per the specification)
                    // but add a new listener from onStartAsync().
                    asyncContext.dispatch(path + "/two");
                }
                else if (requestURI.endsWith("/two"))
                {
                    AsyncContext asyncContext = request.startAsync(request, response);
                    asyncContext.complete();
                }
            }
        });

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            OutputStream output = socket.getOutputStream();

            String request = "" +
                    "GET " + path + "/one HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            SimpleHttpParser parser = new SimpleHttpParser();
            SimpleHttpResponse response = parser.readResponse(reader);
            Assert.assertEquals("200", response.getCode());
            completes.get().await(10,TimeUnit.SECONDS);

            // Send a second request
            completes.set(new CountDownLatch(1));
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = parser.readResponse(reader);
            Assert.assertEquals("200", response.getCode());
            completes.get().await(10,TimeUnit.SECONDS);
        }
    }
}
