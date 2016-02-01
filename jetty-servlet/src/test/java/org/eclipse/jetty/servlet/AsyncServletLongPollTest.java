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

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpParser;
import org.eclipse.jetty.toolchain.test.http.SimpleHttpResponse;
import org.junit.After;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class AsyncServletLongPollTest
{
    @Rule
    public TestTracker tracker = new TestTracker();
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
        context = new ServletContextHandler(server, contextPath, ServletContextHandler.NO_SESSIONS);
        ServletHolder servletHolder = new ServletHolder(servlet);
        String servletPath = "/path";
        context.addServlet(servletHolder, servletPath);
        uri = contextPath + servletPath;
        server.start();
    }

    @After
    public void destroy() throws Exception
    {
        server.stop();
    }

    @Test
    public void testSuspendedRequestCompletedByAnotherRequest() throws Exception
    {
        final CountDownLatch asyncLatch = new CountDownLatch(1);
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

                final AsyncContext asyncContext = this.asyncContext;
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

            Assert.assertTrue(asyncLatch.await(5, TimeUnit.SECONDS));

            String error = "408";
            try (Socket socket2 = new Socket("localhost", connector.getLocalPort()))
            {
                String request2 = "DELETE " + uri + "?error=" + error + " HTTP/1.1\r\n" +
                        "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                        "\r\n";
                OutputStream output2 = socket2.getOutputStream();
                output2.write(request2.getBytes(StandardCharsets.UTF_8));
                output2.flush();

                SimpleHttpParser parser2 = new SimpleHttpParser();
                BufferedReader input2 = new BufferedReader(new InputStreamReader(socket2.getInputStream(), StandardCharsets.UTF_8));
                SimpleHttpResponse response2 = parser2.readResponse(input2);
                Assert.assertEquals("200", response2.getCode());
            }

            socket1.setSoTimeout(2 * wait);
            SimpleHttpParser parser1 = new SimpleHttpParser();
            BufferedReader input1 = new BufferedReader(new InputStreamReader(socket1.getInputStream(), StandardCharsets.UTF_8));
            SimpleHttpResponse response1 = parser1.readResponse(input1);
            Assert.assertEquals(error, response1.getCode());

            // Now try to make another request on the first connection
            // to verify that we set correctly the read interest (#409842)
            String request3 = "GET " + uri + " HTTP/1.1\r\n" +
                    "Host: localhost:" + connector.getLocalPort() + "\r\n" +
                    "\r\n";
            output1.write(request3.getBytes(StandardCharsets.UTF_8));
            output1.flush();

            SimpleHttpResponse response3 = parser1.readResponse(input1);
            Assert.assertEquals("200", response3.getCode());
        }
    }
}
