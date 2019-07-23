//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.util.function.Function;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FormTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;
    private String contextPath = "/ctx";
    private String servletPath = "/test_form";

    private void start(Function<ServletContextHandler, HttpServlet> config) throws Exception
    {
        startServer(config);
        startClient();
    }

    private void startServer(Function<ServletContextHandler, HttpServlet> config) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        ServletContextHandler handler = new ServletContextHandler(server, contextPath);
        HttpServlet servlet = config.apply(handler);
        handler.addServlet(new ServletHolder(servlet), servletPath + "/*");

        server.start();
    }

    private void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testMaxFormContentSizeZeroWithoutContentLength() throws Exception
    {
        testMaxFormContentSizeZero(false);
    }

    @Test
    public void testMaxFormContentSizeZeroWithContentLength() throws Exception
    {
        testMaxFormContentSizeZero(true);

    }

    private void testMaxFormContentSizeZero(boolean addContentLength) throws Exception
    {
        start(handler ->
        {
            handler.setMaxFormContentSize(0);
            return new HttpServlet()
            {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    request.getParameterMap();
                }
            };
        });

        Fields formParams = new Fields();
        formParams.add("foo", "bar");
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .path(contextPath + servletPath)
            .content(new FormContentProvider(formParams)
            {
                @Override
                public long getLength()
                {
                    return addContentLength ? super.getLength() : -1;
                }
            })
            .send();

        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testMaxFormKeysZero() throws Exception
    {
        start(handler ->
        {
            handler.setMaxFormKeys(0);
            return new HttpServlet() {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    request.getParameterMap();
                }
            };
        });

        Fields formParams = new Fields();
        formParams.add("foo1", "bar1");
        formParams.add("foo2", "bar2");
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .path(contextPath + servletPath)
            .content(new FormContentProvider(formParams))
            .send();

        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }
}
