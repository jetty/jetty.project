//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.server.proxy;

import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TryFilesFilterTest
{
    private Server server;
    private ServerConnector connector;
    private ServerConnector sslConnector;
    private HttpClient client;
    private String forwardPath;

    public void prepare(HttpServlet servlet) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        SslContextFactory.Server serverSslContextFactory = new SslContextFactory.Server();
        serverSslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        serverSslContextFactory.setKeyStorePassword("storepwd");
        sslConnector = new ServerConnector(server, serverSslContextFactory);
        server.addConnector(sslConnector);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        FilterHolder filterHolder = context.addFilter(TryFilesFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        forwardPath = "/index.php";
        filterHolder.setInitParameter(TryFilesFilter.FILES_INIT_PARAM, "$path " + forwardPath + "?p=$path");

        context.addServlet(new ServletHolder(servlet), "/*");

        ClientConnector clientConnector = new ClientConnector();
        SslContextFactory.Client clientSslContextFactory = new SslContextFactory.Client();
        clientSslContextFactory.setEndpointIdentificationAlgorithm(null);
        clientSslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        clientSslContextFactory.setKeyStorePassword("storepwd");
        clientConnector.setSslContextFactory(clientSslContextFactory);
        client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        server.addBean(client);

        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testHTTPSRequestIsForwarded() throws Exception
    {
        final String path = "/one/";
        prepare(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            {
                assertTrue("https".equalsIgnoreCase(req.getScheme()));
                assertTrue(req.isSecure());
                assertEquals(forwardPath, req.getRequestURI());
                assertTrue(req.getQueryString().endsWith(path));
            }
        });

        ContentResponse response = client.newRequest("localhost", sslConnector.getLocalPort())
            .scheme("https")
            .path(path)
            .send();

        assertEquals(200, response.getStatus());
    }
}
