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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AsyncServletLongPollTest
{
    private Server server;
    private ServerConnector connector;
    private ServletContextHandler context;
    private String uri;

    protected void prepare(HttpServlet servlet) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);
        String contextPath = "/context";
        context = new ServletContextHandler(contextPath, ServletContextHandler.NO_SESSIONS);
        server.setHandler(context);
        ServletHolder servletHolder = new ServletHolder(servlet);
        String servletPath = "/path";
        context.addServlet(servletHolder, servletPath);
        uri = contextPath + servletPath;
        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
    }

    @Test
    public void testSuspendedRequestCompletedByAnotherRequest() throws Exception
    {
        CountDownLatch asyncLatch = new CountDownLatch(1);
        prepare(new HttpServlet()
        {
            private volatile AsyncContext asyncContext;

            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                int suspend = 0;
                String param = request.getParameter("suspend");
                if (param != null)
                    suspend = Integer.parseInt(param);

                if (suspend > 0)
                {
                    asyncContext = request.startAsync();
                    asyncLatch.countDown();
                }
            }

            @Override
            protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                int error = 0;
                String param = request.getParameter("error");
                if (param != null)
                    error = Integer.parseInt(param);

                AsyncContext asyncContext = this.asyncContext;
                if (asyncContext != null)
                {
                    HttpServletResponse asyncResponse = (HttpServletResponse)asyncContext.getResponse();
                    asyncResponse.sendError(error);
                    asyncContext.complete();
                }
                else
                {
                    response.sendError(404);
                }
            }
        });

        try (Socket socket1 = new Socket("localhost", connector.getLocalPort()))
        {
            int wait = 1000;
            String request1 = "GET " + uri + "?suspend=" + wait + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n";
            OutputStream output1 = socket1.getOutputStream();
            output1.write(request1.getBytes(StandardCharsets.UTF_8));
            output1.flush();

            assertTrue(asyncLatch.await(5, TimeUnit.SECONDS));

            int error = 408;
            try (Socket socket2 = new Socket("localhost", connector.getLocalPort()))
            {
                String request2 = "DELETE " + uri + "?error=" + error + " HTTP/1.1\r\n" +
                    "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                    "\r\n";
                OutputStream output2 = socket2.getOutputStream();
                output2.write(request2.getBytes(StandardCharsets.UTF_8));
                output2.flush();

                HttpTester.Input input2 = HttpTester.from(socket2.getInputStream());
                HttpTester.Response response2 = HttpTester.parseResponse(input2);
                assertEquals(200, response2.getStatus());
            }

            socket1.setSoTimeout(2 * wait);

            HttpTester.Input input1 = HttpTester.from(socket1.getInputStream());
            HttpTester.Response response1 = HttpTester.parseResponse(input1);
            assertEquals(error, response1.getStatus());

            // Now try to make another request on the first connection
            // to verify that we set correctly the read interest (#409842)
            String request3 = "GET " + uri + " HTTP/1.1\r\n" +
                "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                "\r\n";
            output1.write(request3.getBytes(StandardCharsets.UTF_8));
            output1.flush();

            HttpTester.Response response3 = HttpTester.parseResponse(input1);
            assertEquals(200, response3.getStatus());
        }
    }

    @Test
    public void testSuspendedRequestThenServerStop() throws Exception
    {
        AtomicReference<AsyncContext> asyncContextRef = new AtomicReference<>();
        prepare(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                // Suspend the request.
                AsyncContext asyncContext = request.startAsync();
                asyncContextRef.set(asyncContext);
            }

            @Override
            public void destroy()
            {
                // Try to write an error response when shutting down.
                AsyncContext asyncContext = asyncContextRef.get();
                try
                {
                    HttpServletResponse response = (HttpServletResponse)asyncContext.getResponse();
                    response.sendError(HttpStatus.INTERNAL_SERVER_ERROR_500);
                }
                catch (IOException x)
                {
                    throw new RuntimeException(x);
                }
                finally
                {
                    asyncContext.complete();
                }
            }
        });

        try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            HttpTester.Request request = HttpTester.newRequest();
            request.setURI(uri);
            client.write(request.generate());

            await().atMost(5, TimeUnit.SECONDS).until(asyncContextRef::get, Matchers.notNullValue());

            server.stop();

            client.socket().setSoTimeout(1000);
            // The connection has been closed, no response.
            HttpTester.Response response = HttpTester.parseResponse(client);
            assertNull(response);
        }
    }
}
