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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ResponseHeadersTest
{
    public static class SimulateUpgradeServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            response.setHeader("Upgrade","WebSocket");
            response.addHeader("Connection","Upgrade");
            response.addHeader("Sec-WebSocket-Accept","123456789==");

            response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
        }
    }

    public static class MultilineResponseValueServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            // The bad use-case
            String pathInfo = req.getPathInfo();
            if(pathInfo != null && pathInfo.length() > 1 && pathInfo.startsWith("/"))
            {
                pathInfo = pathInfo.substring(1);
            }
            response.setHeader("X-example", pathInfo);

            // The correct use
            response.setContentType("text/plain");
            response.setCharacterEncoding("utf-8");
            response.getWriter().println("Got request uri - " + req.getRequestURI());
        }
    }

    private static Server server;
    private static LocalConnector connector;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(new ServletHolder(new SimulateUpgradeServlet()),"/ws/*");
        context.addServlet(new ServletHolder(new MultilineResponseValueServlet()),"/multiline/*");

        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    @Test
    public void testResponseWebSocketHeaderFormat() throws Exception
    {
        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/ws/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code",response.getStatus(),is(101));
        assertThat("Response Header Upgrade",response.get("Upgrade"),is("WebSocket"));
        assertThat("Response Header Connection",response.get("Connection"),is("Upgrade"));
    }

    @Test
    public void testMultilineResponseHeaderValue() throws Exception
    {
        String actualPathInfo = "%0A%20Content-Type%3A%20image/png%0A%20Content-Length%3A%208%0A%20%0A%20yuck<!--";

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/multiline/" + actualPathInfo);
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code",response.getStatus(),is(200));
        assertThat("Response Header Content-Type",response.get("Content-Type"),is("text/plain;charset=UTF-8"));

        String expected = actualPathInfo.replaceAll("%0A", " "); // replace OBS fold with space
        expected = URLDecoder.decode(expected, "utf-8"); // decode the rest
        expected = expected.trim(); // trim whitespace at start/end
        assertThat("Response Header X-example", response.get("X-Example"), is(expected));
    }
}
