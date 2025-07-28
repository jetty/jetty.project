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

package org.eclipse.jetty.ee9.servlet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.function.Consumer;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
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
                    out.write("<null>".getBytes(UTF_8));
                }
                else
                {
                    IO.copy(in, out);
                }
            }

            String resourceContents = new String(out.toByteArray(), UTF_8);
            resp.getWriter().printf("Resource '%s': %s", pathInfo, resourceContents);
        }
    }

    private Server server;
    private LocalConnector connector;

    private void startServer(Consumer<Server> customizeServer) throws Exception
    {
        server = new Server();

        connector = new LocalConnector(server);
        HttpConfiguration httpConfiguration = connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration();
        httpConfiguration.setSendServerVersion(false);
        httpConfiguration.setSendDateHeader(false);
        server.addConnector(connector);

        customizeServer.accept(server);
        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testGetResourceAsStreamRoot() throws Exception
    {
        startServer(server ->
        {
            Path resBase = MavenPaths.findTestResourceDir("contextResources");
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/context");
            context.setBaseResourceAsPath(resBase);
            context.addServlet(ResourceAsStreamServlet.class, "/*");
            server.setHandler(context);
        });

        String req1 = """
            GET /context/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """;

        String response = connector.getResponse(req1);
        assertThat("Response", response, containsString("Resource '/': <null>"));
    }

    @Test
    public void testGetResourceAsStreamContent() throws Exception
    {
        startServer(server ->
        {
            Path resBase = MavenPaths.findTestResourceDir("contextResources");
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/context");
            context.setBaseResourceAsPath(resBase);
            context.addServlet(ResourceAsStreamServlet.class, "/*");
            server.setHandler(context);
        });

        String req1 = """
            GET /context/content.txt HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """;

        String response = connector.getResponse(req1);
        assertThat("Response", response, containsString("Resource '/content.txt': content goes here"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "space with <p>",
        "/space with %0X",
        "bad pct-encoding %%TOK%%",
        "/bad pct-encoding %%TOK%%"
    })
    public void testGetResourcePathMalformed(String resourceName) throws Exception
    {
        startServer(server ->
        {
            Path resBase = MavenPaths.findTestResourceDir("contextResources");
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/context");
            context.setBaseResourceAsPath(resBase);
            HttpServlet servlet = new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException
                {
                    try
                    {
                        getServletContext().getResource(resourceName);
                        // we shouldn't reach this next line
                        // if we do, then our resourceName isn't sufficiently bad/malformed for this test.
                        resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
                    }
                    catch (MalformedURLException e)
                    {
                        // tell client that this was the correct, expected behavior.
                        resp.setStatus(200);
                        resp.getWriter().println(e.getClass() + ":" + e.getMessage());
                    }
                    catch (Throwable t)
                    {
                        t.printStackTrace(System.err);
                        resp.sendError(500, t.getMessage());
                    }
                }
            };
            ServletHolder servletHolder = new ServletHolder("get-resource-path-malformed", servlet);
            context.addServlet(servletHolder, "/malformed/*");
            server.setHandler(context);
        });

        String req1 = """
            GET /context/malformed/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """;

        String rawResponse = connector.getResponse(req1);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat("response.status", response.getStatus(), is(200));
        String body = response.getContent();
        assertThat("response.body", body, containsString(
            String.format("%s:%s", MalformedURLException.class.getName(), resourceName)));
    }
}
