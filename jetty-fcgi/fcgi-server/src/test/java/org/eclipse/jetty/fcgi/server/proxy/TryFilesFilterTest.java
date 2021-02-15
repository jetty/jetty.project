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

package org.eclipse.jetty.fcgi.server.proxy;

import java.io.IOException;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
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
        serverSslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        serverSslContextFactory.setKeyStorePassword("storepwd");
        sslConnector = new ServerConnector(server, serverSslContextFactory);
        server.addConnector(sslConnector);

        ServletContextHandler context = new ServletContextHandler(server, "/");

        FilterHolder filterHolder = context.addFilter(TryFilesFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        forwardPath = "/index.php";
        filterHolder.setInitParameter(TryFilesFilter.FILES_INIT_PARAM, "$path " + forwardPath + "?p=$path");

        context.addServlet(new ServletHolder(servlet), "/*");

        SslContextFactory.Client clientSslContextFactory = new SslContextFactory.Client();
        clientSslContextFactory.setEndpointIdentificationAlgorithm(null);
        clientSslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        clientSslContextFactory.setKeyStorePassword("storepwd");
        clientSslContextFactory.setTrustStorePath("src/test/resources/truststore.jks");
        clientSslContextFactory.setTrustStorePassword("storepwd");
        client = new HttpClient(clientSslContextFactory);
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
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
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
