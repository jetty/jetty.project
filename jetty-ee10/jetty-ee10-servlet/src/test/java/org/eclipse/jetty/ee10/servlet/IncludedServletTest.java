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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class IncludedServletTest
{
    public static class TopServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            req.getRequestDispatcher("/included").include(req, resp);
            resp.setHeader("main-page-key", "main-page-value");

            PrintWriter out = resp.getWriter();
            out.println("<h2> Hello, this is the top page.");
        }
    }

    public static class IncludedServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            String headerPrefix = "";
            if (req.getDispatcherType() == DispatcherType.INCLUDE)
                headerPrefix = "org.eclipse.jetty.server.include.";

            resp.setHeader(headerPrefix + "included-page-key", "included-page-value");
            resp.getWriter().println("<h3> This is the included page");
        }
    }

    public static class IncludedAttrServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            if (req.getDispatcherType() == DispatcherType.INCLUDE)
            {
                if (req.getAttribute("included") == null)
                {
                    req.setAttribute("included", Boolean.TRUE);
                    dumpAttrs("BEFORE1", req, resp.getOutputStream());
                    req.getRequestDispatcher("two").include(req, resp);
                    dumpAttrs("AFTER1", req, resp.getOutputStream());
                }
                else
                {
                    dumpAttrs("DURING", req, resp.getOutputStream());
                }
            }
            else
            {
                resp.setContentType("text/plain");
                dumpAttrs("BEFORE0", req, resp.getOutputStream());
                req.getRequestDispatcher("one").include(req, resp);
                dumpAttrs("AFTER0", req, resp.getOutputStream());
            }
        }

        private void dumpAttrs(String tag, HttpServletRequest req, ServletOutputStream out) throws IOException
        {
            String contextPath = (String)req.getAttribute(RequestDispatcher.INCLUDE_CONTEXT_PATH);
            String servletPath = (String)req.getAttribute(RequestDispatcher.INCLUDE_SERVLET_PATH);
            String pathInfo = (String)req.getAttribute(RequestDispatcher.INCLUDE_PATH_INFO);

            out.println(String.format("%s: %s='%s'", tag, RequestDispatcher.INCLUDE_CONTEXT_PATH, contextPath));
            out.println(String.format("%s: %s='%s'", tag, RequestDispatcher.INCLUDE_SERVLET_PATH, servletPath));
            out.println(String.format("%s: %s='%s'", tag, RequestDispatcher.INCLUDE_PATH_INFO, pathInfo));
        }
    }

    private Server server;
    private URI baseUri;

    @BeforeEach
    public void startServer() throws Exception
    {
        this.server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setHost("localhost");
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(TopServlet.class, "/top");
        context.addServlet(IncludedServlet.class, "/included");
        context.addServlet(IncludedAttrServlet.class, "/attr/*");

        server.setHandler(context);

        server.start();

        int port = connector.getLocalPort();
        String host = connector.getHost();

        baseUri = URI.create("http://" + host + ":" + port + "/");
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        this.server.stop();
    }

    @Disabled
    @Test
    public void testTopWithIncludedHeader() throws IOException
    {
        URI uri = baseUri.resolve("/top");
        System.out.println("GET (String): " + uri.toASCIIString());

        InputStream in = null;
        InputStreamReader reader = null;
        HttpURLConnection connection = null;

        try
        {
            connection = (HttpURLConnection)uri.toURL().openConnection();
            connection.connect();
            if (HttpURLConnection.HTTP_OK != connection.getResponseCode())
            {
                String body = getPotentialBody(connection);
                String err = String.format("GET request failed (%d %s) %s%n%s", connection.getResponseCode(), connection.getResponseMessage(),
                    uri.toASCIIString(), body);
                throw new IOException(err);
            }
            in = connection.getInputStream();
            reader = new InputStreamReader(in);
            StringWriter writer = new StringWriter();
            IO.copy(reader, writer);

            String response = writer.toString();
            // System.out.printf("Response%n%s",response);
            assertThat("Response", response, containsString("<h2> Hello, this is the top page."));
            assertThat("Response", response, containsString("<h3> This is the included page"));

            assertThat("Response Header[main-page-key]", connection.getHeaderField("main-page-key"), is("main-page-value"));
            assertThat("Response Header[included-page-key]", connection.getHeaderField("included-page-key"), is("included-page-value"));
        }
        finally
        {
            IO.close(reader);
            IO.close(in);
        }
    }

    /**
     * Attempt to obtain the body text if available. Do not throw an exception if body is unable to be fetched.
     *
     * @param connection the connection to fetch the body content from.
     * @return the body content, if present.
     */
    private String getPotentialBody(HttpURLConnection connection)
    {
        InputStream in = null;
        InputStreamReader reader = null;
        try
        {
            in = connection.getInputStream();
            reader = new InputStreamReader(in);
            StringWriter writer = new StringWriter();
            IO.copy(reader, writer);
            return writer.toString();
        }
        catch (IOException e)
        {
            return "<no body:" + e.getMessage() + ">";
        }
        finally
        {
            IO.close(reader);
            IO.close(in);
        }
    }

    @Test
    public void testIncludeAttributes() throws IOException
    {
        URI uri = baseUri.resolve("/attr/one");
        InputStream in = null;
        BufferedReader reader = null;
        HttpURLConnection connection = null;

        try
        {
            connection = (HttpURLConnection)uri.toURL().openConnection();
            connection.connect();
            assertThat(connection.getResponseCode(), is(HttpURLConnection.HTTP_OK));
            in = connection.getInputStream();
            reader = new BufferedReader(new InputStreamReader(in));
            List<String> result = new ArrayList<>();
            String line = reader.readLine();
            while (line != null)
            {
                result.add(line);
                line = reader.readLine();
            }

            assertThat(result, Matchers.contains(
                "BEFORE0: jakarta.servlet.include.context_path='null'",
                "BEFORE0: jakarta.servlet.include.servlet_path='null'",
                "BEFORE0: jakarta.servlet.include.path_info='null'",
                "BEFORE1: jakarta.servlet.include.context_path=''",
                "BEFORE1: jakarta.servlet.include.servlet_path='/attr'",
                "BEFORE1: jakarta.servlet.include.path_info='/one'",
                "DURING: jakarta.servlet.include.context_path=''",
                "DURING: jakarta.servlet.include.servlet_path='/attr'",
                "DURING: jakarta.servlet.include.path_info='/two'",
                "AFTER1: jakarta.servlet.include.context_path=''",
                "AFTER1: jakarta.servlet.include.servlet_path='/attr'",
                "AFTER1: jakarta.servlet.include.path_info='/one'",
                "AFTER0: jakarta.servlet.include.context_path='null'",
                "AFTER0: jakarta.servlet.include.servlet_path='null'",
                "AFTER0: jakarta.servlet.include.path_info='null'"
            ));
        }
        finally
        {
            IO.close(reader);
            IO.close(in);
        }
    }
}
