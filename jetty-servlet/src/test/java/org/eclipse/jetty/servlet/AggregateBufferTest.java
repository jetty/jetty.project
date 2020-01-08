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
import java.io.OutputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AggregateBufferTest
{
    private Server server;
    private URI serverUri;
    private HttpClient client;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        HttpConfiguration config = connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration();
        config.setOutputBufferSize(32 * 1024);
        config.setOutputAggregationSize(8 * 1024);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(AggServlet.class, "/agg");

        GzipHandler gzip = new GzipHandler();
        gzip.setHandler(context);

        server.setHandler(gzip);

        server.start();
        serverUri = server.getURI().resolve("/");
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @Test
    public void testAggregateBehaviors() throws InterruptedException, ExecutionException, TimeoutException
    {
        ContentResponse response = client.GET(serverUri.resolve("/agg"));
        assertEquals(200, response.getStatus());
    }

    public static class AggServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            OutputStream out = resp.getOutputStream();
            writeBuf(out, 'r', 7149);
            writeBuf(out, 's', 8000);
            writeBuf(out, 't', 4440);
            writeBuf(out, 'u', 3004);
            writeBuf(out, 'v', 7981);
            writeBuf(out, 'w', 8000);
            out.flush();
        }

        private void writeBuf(OutputStream out, char c, int size) throws IOException
        {
            byte[] buf = new byte[8 * 1024];
            Arrays.fill(buf, 0, size, (byte)c);
            out.write(buf, 0, size);
        }
    }
}
