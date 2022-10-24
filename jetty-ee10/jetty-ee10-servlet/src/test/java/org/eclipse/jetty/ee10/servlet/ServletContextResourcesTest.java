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

package org.eclipse.jetty.ee10.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class ServletContextResourcesTest
{
    public static class ResourceAsStreamServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            String pathInfo = req.getPathInfo();

            ByteArrayOutputStream out = new ByteArrayOutputStream();

            try (InputStream in = req.getServletContext().getResourceAsStream(pathInfo))
            {
                if (in == null)
                {
                    out.write("<null>".getBytes(StandardCharsets.UTF_8));
                }
                else
                {
                    IO.copy(in, out);
                }
            }

            String resourceContents = new String(out.toByteArray(), StandardCharsets.UTF_8);
            resp.getWriter().printf("Resource '%s': %s", pathInfo, resourceContents);
        }
    }

    private Server server;
    private LocalConnector connector;
    private ServletContextHandler context;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();

        connector = new LocalConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);

        Path resBase = MavenTestingUtils.getTestResourcePathDir("contextResources");

        context = new ServletContextHandler();
        context.setContextPath("/context");
        context.setBaseResourceAsPath(resBase);

        server.setHandler(context);
        server.addConnector(connector);

        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testGetResourceAsStreamRoot() throws Exception
    {
        context.addServlet(ResourceAsStreamServlet.class, "/*");

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /context/ HTTP/1.1\r\n");
        req1.append("Host: local\r\n");
        req1.append("Connection: close\r\n");
        req1.append("\r\n");

        String response = connector.getResponse(req1.toString());
        assertThat("Response", response, containsString("Resource '/': <null>"));
    }

    @Test
    public void testGetResourceAsStreamContent() throws Exception
    {
        context.addServlet(ResourceAsStreamServlet.class, "/*");

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /context/content.txt HTTP/1.1\r\n");
        req1.append("Host: local\r\n");
        req1.append("Connection: close\r\n");
        req1.append("\r\n");

        String response = connector.getResponse(req1.toString());
        assertThat("Response", response, containsString("Resource '/content.txt': content goes here"));
    }
}
