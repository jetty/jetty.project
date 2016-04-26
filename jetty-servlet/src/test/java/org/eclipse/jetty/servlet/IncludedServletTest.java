//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URI;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.IO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

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

    private Server server;
    private URI baseUri;

    @Before
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

        server.setHandler(context);

        server.start();

        int port = connector.getLocalPort();
        String host = connector.getHost();

        baseUri = URI.create("http://" + host + ":" + port + "/");
    }

    @After
    public void stopServer() throws Exception
    {
        this.server.stop();
    }

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
            connection = (HttpURLConnection) uri.toURL().openConnection();
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
}
