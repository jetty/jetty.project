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

package org.eclipse.jetty.ee9.proxy;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
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
