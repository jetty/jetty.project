//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
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
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ReverseProxyTest
{
    private Server server;
    private ServerConnector serverConnector;
    private Server proxy;
    private ServerConnector proxyConnector;
    private HttpClient client;

    private void startServer(HttpServlet servlet) throws Exception
    {
        server = new Server();

        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);

        ServletContextHandler appCtx = new ServletContextHandler(server, "/", true, false);
        ServletHolder appServletHolder = new ServletHolder(servlet);
        appCtx.addServlet(appServletHolder, "/*");

        server.start();
    }

    private void startProxy(Map<String, String> params) throws Exception
    {
        proxy = new Server();

        HttpConfiguration configuration = new HttpConfiguration();
        configuration.setSendDateHeader(false);
        configuration.setSendServerVersion(false);
        proxyConnector = new ServerConnector(proxy, new HttpConnectionFactory(configuration));
        proxy.addConnector(proxyConnector);

        ServletContextHandler proxyContext = new ServletContextHandler(proxy, "/", true, false);
        ServletHolder proxyServletHolder = new ServletHolder(new AsyncMiddleManServlet()
        {
            @Override
            protected String rewriteTarget(HttpServletRequest clientRequest)
            {
                StringBuilder builder = new StringBuilder();
                builder.append(clientRequest.getScheme()).append("://127.0.0.1:");
                builder.append(serverConnector.getLocalPort());
                builder.append(clientRequest.getRequestURI());
                String query = clientRequest.getQueryString();
                if (query != null)
                    builder.append("?").append(query);
                return builder.toString();
            }
        });
        if (params != null)
            proxyServletHolder.setInitParameters(params);

        proxyContext.addServlet(proxyServletHolder, "/*");

        proxy.start();
    }

    private void startClient() throws Exception
    {
        client = new HttpClient();
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        client.stop();
        proxy.stop();
        server.stop();
    }

    @Test
    public void testHostHeaderUpdatedWhenSentToServer() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                assertEquals("127.0.0.1", request.getServerName());
                assertEquals(serverConnector.getLocalPort(), request.getServerPort());
            }
        });
        startProxy(null);
        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort()).send();
        assertEquals(200, response.getStatus());
    }

    @Test
    public void testHostHeaderPreserved() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                assertEquals("localhost", request.getServerName());
                assertEquals(proxyConnector.getLocalPort(), request.getServerPort());
            }
        });
        startProxy(new HashMap<String, String>()
        {
            {
                put("preserveHost", "true");
            }
        });
        startClient();

        ContentResponse response = client.newRequest("localhost", proxyConnector.getLocalPort()).send();
        assertEquals(200, response.getStatus());
    }
}
