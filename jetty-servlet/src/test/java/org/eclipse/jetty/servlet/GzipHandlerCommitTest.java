//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

public class GzipHandlerCommitTest
{
    private static Server server;
    private static HttpClient client;

    @BeforeEach
    public void startUp() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        contextHandler.addServlet(FlushBufferServlet.class, "/flush-buffer/*");

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

    /**
     * A servlet should be able to flush and then produce no content.
     */
    @Test
    public void testFlushNoContent() throws Exception
    {
        long delay = 2000;
        AtomicLong requestCommitTimestamp = new AtomicLong(-1);
        AtomicLong responseBeganTimestamp = new AtomicLong(-1);
        AtomicLong responseHeadersTimestamp = new AtomicLong(-1);
        URI uri = server.getURI().resolve("/flush-buffer/?size=0&delay=" + delay);
        Request request = client.newRequest(uri);
        request.header(HttpHeader.CONNECTION, "Close");
        request.onRequestCommit((r) -> requestCommitTimestamp.set(System.currentTimeMillis()));
        request.onResponseBegin((r) -> responseBeganTimestamp.set(System.currentTimeMillis()));
        request.onResponseHeaders((r) -> responseHeadersTimestamp.set(System.currentTimeMillis()));
        ContentResponse response = request.send();
        assertThat("Response status", response.getStatus(), is(200));
        long responseCommitDuration = responseHeadersTimestamp.get() - requestCommitTimestamp.get();
        assertThat("Response headers duration", responseCommitDuration, lessThan(delay));
    }

    /**
     * A servlet should be able to flush, response is committed, and then content is produced.
     */
    @Test
    public void testFlushThenSomeContent() throws Exception
    {
        int size = 8000;
        long delay = 2000;
        AtomicLong requestCommitTimestamp = new AtomicLong(-1);
        AtomicLong responseBeganTimestamp = new AtomicLong(-1);
        AtomicLong responseHeadersTimestamp = new AtomicLong(-1);
        URI uri = server.getURI().resolve("/flush-buffer/?size=" + size + "&delay=" + delay);
        Request request = client.newRequest(uri);
        request.header(HttpHeader.CONNECTION, "Close");
        request.onRequestCommit((r) -> requestCommitTimestamp.set(System.currentTimeMillis()));
        request.onResponseBegin((r) -> responseBeganTimestamp.set(System.currentTimeMillis()));
        request.onResponseHeaders((r) -> responseHeadersTimestamp.set(System.currentTimeMillis()));
        ContentResponse response = request.send();
        assertThat("Response status", response.getStatus(), is(200));
        long responseCommitDuration = responseHeadersTimestamp.get() - requestCommitTimestamp.get();
        assertThat("Response headers duration", responseCommitDuration, lessThan(delay));
    }

    public static class FlushBufferServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            int delay = Integer.parseInt(request.getParameter("delay"));
            int size = Integer.parseInt(request.getParameter("size"));

            response.setContentType("text/plain");
            ServletOutputStream out = response.getOutputStream();
            response.flushBuffer();

            if (delay > 0)
            {
                try
                {
                    Thread.sleep(delay);
                }
                catch (InterruptedException ignored)
                {
                }
            }

            byte[] buf = new byte[size];
            if (size > 0)
            {
                Arrays.fill(buf, (byte)'a');
                out.write(buf);
            }
        }
    }
}
