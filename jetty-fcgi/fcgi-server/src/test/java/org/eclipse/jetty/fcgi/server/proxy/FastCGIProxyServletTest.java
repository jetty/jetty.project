//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.net.URI;
import java.util.Random;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.fcgi.server.ServerFCGIConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class FastCGIProxyServletTest
{
    private Server server;
    private ServerConnector httpConnector;
    private ServerConnector fcgiConnector;
    private HttpClient client;

    public void prepare(HttpServlet servlet) throws Exception
    {
        server = new Server();
        httpConnector = new ServerConnector(server);
        server.addConnector(httpConnector);

        fcgiConnector = new ServerConnector(server, new ServerFCGIConnectionFactory(new HttpConfiguration()));
        server.addConnector(fcgiConnector);

        final String contextPath = "/";
        ServletContextHandler context = new ServletContextHandler(server, contextPath);

        final String servletPath = "/script";
        FastCGIProxyServlet fcgiServlet = new FastCGIProxyServlet()
        {
            @Override
            protected URI rewriteURI(HttpServletRequest request)
            {
                return URI.create("http://localhost:" + fcgiConnector.getLocalPort() + servletPath + request.getServletPath());
            }
        };
        ServletHolder fcgiServletHolder = new ServletHolder(fcgiServlet);
        context.addServlet(fcgiServletHolder, "*.php");
        fcgiServletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_ROOT_INIT_PARAM, "/scriptRoot");
        fcgiServletHolder.setInitParameter("proxyTo", "http://localhost");
        fcgiServletHolder.setInitParameter(FastCGIProxyServlet.SCRIPT_PATTERN_INIT_PARAM, "(.+?\\.php)");

        context.addServlet(new ServletHolder(servlet), servletPath + "/*");

        client = new HttpClient();
        server.addBean(client);

        server.start();
    }

    @After
    public void dispose() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGETWithSmallResponseContent() throws Exception
    {
        final byte[] data = new byte[1024];
        new Random().nextBytes(data);

        final String path = "/foo/index.php";
        prepare(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                Assert.assertTrue(req.getRequestURI().endsWith(path));
                resp.getOutputStream().write(data);
            }
        });

        ContentResponse response = client.newRequest("localhost", httpConnector.getLocalPort())
                .path(path)
                .send();
        Assert.assertEquals(200, response.getStatus());
        Assert.assertArrayEquals(data, response.getContent());
    }
}
