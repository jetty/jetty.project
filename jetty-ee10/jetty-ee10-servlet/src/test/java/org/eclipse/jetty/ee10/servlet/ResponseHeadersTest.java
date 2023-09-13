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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class ResponseHeadersTest
{
    private Server server;
    private LocalConnector connector;

    public void startServer(ServletContextHandler contextHandler) throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        server.setHandler(contextHandler);
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testResponseWebSocketHeaderFormat() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet fakeWsServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                response.setHeader("Upgrade", "WebSocket");
                response.addHeader("Connection", "Upgrade");
                response.addHeader("Sec-WebSocket-Accept", "123456789==");

                response.setStatus(HttpServletResponse.SC_SWITCHING_PROTOCOLS);
            }
        };
        contextHandler.addServlet(fakeWsServlet, "/ws/*");

        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/ws/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(101));
        assertThat("Response Header Upgrade", response.get("Upgrade"), is("WebSocket"));
        assertThat("Response Header Connection", response.get("Connection"), is("Upgrade"));
    }

    @Test
    public void testMultilineResponseHeaderValue() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        HttpServlet multilineServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
            {
                // The bad use-case
                String pathInfo = URIUtil.decodePath(req.getPathInfo());
                if (pathInfo != null && pathInfo.length() > 1 && pathInfo.startsWith("/"))
                {
                    pathInfo = pathInfo.substring(1);
                }
                response.setHeader("X-example", pathInfo);

                // The correct use
                response.setContentType("text/plain");
                response.setCharacterEncoding("utf-8");
                response.getWriter().println("Got request uri - " + req.getRequestURI());
            }
        };

        contextHandler.addServlet(multilineServlet, "/multiline/*");
        startServer(contextHandler);

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
        assertThat("Response Code", response.getStatus(), is(200));
        assertThat("Response Header Content-Type", response.get("Content-Type"), is("text/plain;charset=UTF-8"));

        String expected = StringUtil.replace(actualPathInfo, "%0A", " "); // replace OBS fold with space
        expected = URLDecoder.decode(expected, StandardCharsets.UTF_8); // decode the rest
        expected = expected.trim(); // trim whitespace at start/end
        assertThat("Response Header X-example", response.get("X-Example"), is(expected));
    }

    @Test
    public void testCharsetResetToJsonMimeType() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");

        HttpServlet charsetResetToJsonMimeTypeServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // We set an initial desired behavior.
                response.setContentType("text/html; charset=US-ASCII");
                PrintWriter writer = response.getWriter();

                // We reset the response, as we don't want it to be HTML anymore.
                response.reset();

                // switch to json operation
                // The use of application/json is always assumed to be UTF-8
                // and should never have a `charset=` entry on the `Content-Type` response header
                response.setContentType("application/json");
                writer.println("{ \"what\": \"should this be?\" }");
            }
        };

        contextHandler.addServlet(charsetResetToJsonMimeTypeServlet, "/charset/json-reset/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/charset/json-reset/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The Content-Type should not have a charset= portion
        assertThat("Response Header Content-Type", response.get("Content-Type"), is("application/json"));
    }

    @Test
    public void testCharsetChangeToJsonMimeType() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet charsetChangeToJsonMimeTypeServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // We set an initial desired behavior.
                response.setContentType("text/html; charset=US-ASCII");

                // switch to json behavior.
                // The use of application/json is always assumed to be UTF-8
                // and should never have a `charset=` entry on the `Content-Type` response header
                response.setContentType("application/json");

                PrintWriter writer = response.getWriter();
                writer.println("{ \"what\": \"should this be?\" }");
            }
        };

        contextHandler.addServlet(charsetChangeToJsonMimeTypeServlet, "/charset/json-change/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/charset/json-change/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The Content-Type should not have a charset= portion
        assertThat("Response Header Content-Type", response.get("Content-Type"), is("application/json"));
    }

    @Test
    public void testCharsetChangeToJsonMimeTypeSetCharsetToNull() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet charsetChangeToJsonMimeTypeSetCharsetToNullServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // We set an initial desired behavior.

                response.setContentType("text/html; charset=us-ascii");
                PrintWriter writer = response.getWriter();

                // switch to json behavior.
                // The use of application/json is always assumed to be UTF-8
                // and should never have a `charset=` entry on the `Content-Type` response header
                response.setContentType("application/json");
                // attempt to indicate that there is truly no charset meant to be used in the response header
                response.setCharacterEncoding(null);

                writer.println("{ \"what\": \"should this be?\" }");
            }
        };

        contextHandler.addServlet(charsetChangeToJsonMimeTypeSetCharsetToNullServlet, "/charset/json-change-null/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/charset/json-change-null/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        // System.err.println(BufferUtil.toUTF8String(responseBuffer));
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        // Now test for properly formatted HTTP Response Headers.
        assertThat("Response Code", response.getStatus(), is(200));
        // The Content-Type should not have a charset= portion
        assertThat("Response Header Content-Type", response.get("Content-Type"), is("application/json"));
    }

    @Test
    public void testFlushPrintWriter() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet flushResponseServlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                PrintWriter writer = response.getWriter();
                writer.println("Hello");
                writer.flush();
                if (!response.isCommitted())
                    throw new IllegalStateException();
                writer.println("World");
                writer.close();
            }
        };

        contextHandler.addServlet(flushResponseServlet, "/flush/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/flush");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat("Response Code", response.getStatus(), is(200));
        assertThat(response.getContent(), equalTo("Hello\nWorld\n"));
    }
}
