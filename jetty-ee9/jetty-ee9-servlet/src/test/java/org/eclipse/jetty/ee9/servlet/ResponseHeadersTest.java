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

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResponseHeadersTest
{
    public static class WrappingFilter extends HttpFilter
    {
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException
        {
            this.doFilter((HttpServletRequest)req, new HttpServletResponseWrapper((HttpServletResponse)res), chain);
        }
    }

    public static class SimulateUpgradeServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse response) throws ServletException, IOException
        {
            response.setHeader("Upgrade", "WebSocket");
            response.addHeader("Connection", "Upgrade");
            response.addHeader("Sec-WebSocket-Accept", "123456789==");

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
    }

    public static class CharsetResetToJsonMimeTypeServlet extends HttpServlet
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
    }

    public static class CharsetChangeToJsonMimeTypeServlet extends HttpServlet
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
    }

    public static class CharsetChangeToJsonMimeTypeSetCharsetToNullServlet extends HttpServlet
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
    }

    private Server server;
    private LocalConnector connector;

    public void startServer() throws Exception
    {
        startServer((context) ->
        {
            context.addServlet(new ServletHolder(new DefaultServlet()), "/default/*");
            context.addFilter(new FilterHolder(new WrappingFilter()), "/default/*", EnumSet.allOf(DispatcherType.class));
            context.addServlet(new ServletHolder(new SimulateUpgradeServlet()), "/ws/*");
            context.addServlet(new ServletHolder(new MultilineResponseValueServlet()), "/multiline/*");
            context.addServlet(CharsetResetToJsonMimeTypeServlet.class, "/charset/json-reset/*");
            context.addServlet(CharsetChangeToJsonMimeTypeServlet.class, "/charset/json-change/*");
            context.addServlet(CharsetChangeToJsonMimeTypeSetCharsetToNullServlet.class, "/charset/json-change-null/*");
        });
    }

    public void startServer(Consumer<ServletContextHandler> configureServletContext) throws Exception
    {
        Path staticContentPath = MavenPaths.findTestResourceDir("contextResources");
        server = new Server();
        connector = new LocalConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setBaseResourceAsPath(staticContentPath);
        context.setInitParameter("org.eclipse.jetty.servlet.Default.pathInfoOnly", "TRUE");

        configureServletContext.accept(context);

        server.setHandler(context);
        server.start();
    }

    @AfterEach
    public void stopServer()
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
    public void testWrappedResponseWithStaticContent() throws Exception
    {
        startServer();
        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/default/test.txt");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);
        assertTrue(response.getContent().startsWith("Test 2"));
    }

    @Test
    public void testResponseWebSocketHeaderFormat() throws Exception
    {
        startServer();
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
        startServer();
        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        String actualPathInfo = "%0a%20Content-Type%3a%20image/png%0a%20Content-Length%3a%208%0A%20%0A%20yuck<!--";

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

        String expected = StringUtil.replace(actualPathInfo, "%0a", " ");  // replace OBS fold with space
        expected = StringUtil.replace(expected, "%0A", " "); // replace OBS fold with space
        expected = URLDecoder.decode(expected, StandardCharsets.UTF_8); // decode the rest
        expected = expected.trim(); // trim whitespace at start/end
        assertThat("Response Header X-example", response.get("X-Example"), is(expected));
    }

    @Test
    public void testCharsetResetToJsonMimeType() throws Exception
    {
        startServer();
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
        startServer();
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
        startServer();
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

    public static Stream<Arguments> http10ConnectionBehavior()
    {
        List<Arguments> cases = new ArrayList<>();
        String request;

        // --- VALID HTTP/1.0 SPEC REQUESTS ---

        // Request does not send connection header.
        request = null;

        cases.add(Arguments.of(request, null, "close"));
        cases.add(Arguments.of(request, "close", "close"));
        cases.add(Arguments.of(request, "keep-alive", "close"));

        // Request sends "keep-alive"
        request = "keep-alive";

        cases.add(Arguments.of(request, null, "keep-alive"));
        cases.add(Arguments.of(request, "close", "close"));
        cases.add(Arguments.of(request, "keep-alive", "keep-alive"));

        // --- INVALID HTTP/1.0 SPEC REQUESTS ---

        // Request sends invalid value "close" (on HTTP/1.0)
        request = "close";

        cases.add(Arguments.of(request, null, "close"));
        cases.add(Arguments.of(request, "close", "close"));
        cases.add(Arguments.of(request, "keep-alive", "close"));

        // --- INVALID HTTP RESPONSE HEADERS ---
        // this is when the servlet is setting headers that are in conflict with
        // the spec and each other.

        // Request does not set "connection" header.
        request = null;

        cases.add(Arguments.of(request, "close, keep-alive", "close"));
        cases.add(Arguments.of(request, "keep-alive, close", "close"));

        // Request sends invalid value "close" (on HTTP/1.0)
        request = "close";

        // keep-alive is forbidden when not persistent
        cases.add(Arguments.of(request, "close, keep-alive", "close"));
        cases.add(Arguments.of(request, "keep-alive, close", "close"));

        // Request sends "keep-alive"
        request = "keep-alive";

        // connection lists are preserved per hop-by-hop rules
        cases.add(Arguments.of(request, "close, keep-alive", "close, keep-alive"));
        cases.add(Arguments.of(request, "keep-alive, close", "keep-alive, close"));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("http10ConnectionBehavior")
    public void testHTTP10ConnectionBehavior(String requestConnectionHeader, String responseConnectionHeader, String expectedConnectionHeader) throws Exception
    {
        startServer((context) ->
        {
            HttpServlet servlet = new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                {
                    if (responseConnectionHeader != null)
                        resp.setHeader("Connection", responseConnectionHeader);
                }
            };
            ServletHolder servletHolder = new ServletHolder(servlet);
            context.addServlet(servletHolder, "/nothing");
        });

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /nothing HTTP/1.0\r\n");
        rawRequest.append("Host: test\r\n");
        if (requestConnectionHeader != null)
        {
            rawRequest.append("Connection: ").append(requestConnectionHeader).append("\r\n");
        }
        rawRequest.append("\r\n");

        String rawResponse = connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        if (expectedConnectionHeader == null)
            assertThat(response.get(HttpHeader.CONNECTION), nullValue());
        else
            assertThat(response.get(HttpHeader.CONNECTION), is(expectedConnectionHeader));
    }

    public static Stream<Arguments> http11ConnectionBehavior()
    {
        List<Arguments> cases = new ArrayList<>();
        String request;

        // --- VALID HTTP/1.1 SPEC REQUESTS ---

        // Request does not send connection header.
        request = null;

        cases.add(Arguments.of(request, null, null));
        cases.add(Arguments.of(request, "close", "close"));
        cases.add(Arguments.of(request, "keep-alive", null));

        // Request sends value "close"
        request = "close";

        cases.add(Arguments.of(request, null, "close"));
        cases.add(Arguments.of(request, "close", "close"));
        cases.add(Arguments.of(request, "keep-alive", "close"));

        // --- INVALID HTTP/1.0 SPEC REQUESTS ---

        // Request sends invalid "keep-alive" header value (on HTTP/1.1)
        request = "keep-alive";

        cases.add(Arguments.of(request, null, null));
        cases.add(Arguments.of(request, "close", "close"));
        cases.add(Arguments.of(request, "keep-alive", null));

        // --- INVALID HTTP RESPONSE HEADERS ---
        // this is when the servlet is setting headers that are in conflict with
        // the spec and each other.

        // Request does not set "connection" header.
        request = null;

        cases.add(Arguments.of(request, "close, keep-alive", "close"));
        cases.add(Arguments.of(request, "keep-alive, close", "close"));

        // Request sends value "close"
        request = "close";

        cases.add(Arguments.of(request, "close, keep-alive", "close"));
        cases.add(Arguments.of(request, "keep-alive, close", "close"));

        // Request sends invalid "keep-alive" header value (on HTTP/1.1)
        request = "keep-alive";

        cases.add(Arguments.of(request, "close, keep-alive", "close"));
        cases.add(Arguments.of(request, "keep-alive, close", "close"));

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("http11ConnectionBehavior")
    public void testHTTP11ConnectionBehavior(String requestConnectionHeader, String responseConnectionHeader, String expectedConnectionHeader) throws Exception
    {
        startServer((context) ->
        {
            HttpServlet servlet = new HttpServlet()
            {
                @Override
                protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                {
                    if (responseConnectionHeader != null)
                        resp.setHeader("Connection", responseConnectionHeader);
                }
            };
            ServletHolder servletHolder = new ServletHolder(servlet);
            context.addServlet(servletHolder, "/nothing");
        });

        StringBuilder rawRequest = new StringBuilder();
        rawRequest.append("GET /nothing HTTP/1.1\r\n");
        rawRequest.append("Host: test\r\n");
        if (requestConnectionHeader != null)
        {
            rawRequest.append("Connection: ").append(requestConnectionHeader).append("\r\n");
        }
        rawRequest.append("\r\n");

        String rawResponse = connector.getResponse(rawRequest.toString());
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getVersion(), is(HttpVersion.HTTP_1_1));
        if (expectedConnectionHeader == null)
            assertThat(response.get(HttpHeader.CONNECTION), nullValue());
        else
            assertThat(response.get(HttpHeader.CONNECTION), is(expectedConnectionHeader));
    }
}
