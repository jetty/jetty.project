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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.Servlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GzipHandlerCommitTest
{
    private Server server;
    private HttpClient client;

    public void start(Servlet servlet) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        ServletHolder servletHolder = new ServletHolder(servlet);
        contextHandler.addServlet(servletHolder, "/test/*");

        GzipHandler gzipHandler = new GzipHandler();
        gzipHandler.setHandler(contextHandler);

        server.setHandler(gzipHandler);
        server.start();

        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testImmediateFlushNoContent() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.flushBuffer();
                assertDoesNotThrow(() -> assertTrue(latch.await(1, TimeUnit.SECONDS)));
            }
        });

        URI uri = server.getURI().resolve("/test/");
        Request request = client.newRequest(uri);
        request.header(HttpHeader.CONNECTION, "Close");
        request.onResponseHeaders((r) -> latch.countDown());
        ContentResponse response = request.send();
        assertThat("Response status", response.getStatus(), is(200));
    }

    @Test
    public void testImmediateFlushWithContent() throws Exception
    {
        int size = 8000;
        CountDownLatch latch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.flushBuffer();
                assertDoesNotThrow(() -> assertTrue(latch.await(1, TimeUnit.SECONDS)));
                response.getOutputStream();
                byte[] buf = new byte[size];
                Arrays.fill(buf, (byte)'a');
                response.getOutputStream().write(buf);
            }
        });

        URI uri = server.getURI().resolve("/test/");
        Request request = client.newRequest(uri);
        request.header(HttpHeader.CONNECTION, "Close");
        request.onResponseHeaders((r) -> latch.countDown());
        ContentResponse response = request.send();
        assertThat("Response status", response.getStatus(), is(200));
        assertThat("Response content size", response.getContent().length, is(size));
    }
}
