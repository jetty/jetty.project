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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled
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

    @AfterEach
    public void dispose() throws Exception
    {
        _server.stop();
    }

    @SuppressWarnings("Duplicates")
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

            String request =
                "GET " + path + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(200, response.getStatus());
            completes.get().await(10, TimeUnit.SECONDS);

            // Send a second request
            completes.set(new CountDownLatch(1));
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(input);
            assertEquals(200, response.getStatus());
            completes.get().await(10, TimeUnit.SECONDS);
        }
    }

    @SuppressWarnings("Duplicates")
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

            String request =
                "GET " + path + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(200, response.getStatus());
            completes.get().await(10, TimeUnit.SECONDS);

            // Send a second request
            completes.set(new CountDownLatch(1));
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(input);
            assertEquals(200, response.getStatus());
            completes.get().await(10, TimeUnit.SECONDS);
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

            String request =
                "GET " + path + "/one HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertEquals(200, response.getStatus());
            completes.get().await(10, TimeUnit.SECONDS);

            // Send a second request
            completes.set(new CountDownLatch(1));
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(input);
            assertEquals(200, response.getStatus());
            completes.get().await(10, TimeUnit.SECONDS);
        }
    }
}
