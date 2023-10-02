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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

public class RequestTest
{
    private Server _server;
    private LocalConnector _connector;

    private void startServer(HttpServlet servlet) throws Exception
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        http.getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());

        _connector = new LocalConnector(_server, http);
        _server.addConnector(_connector);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.addServlet(servlet, "/*");

        _server.setHandler(servletContextHandler);
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        LifeCycle.stop(_server);
    }

    @Test
    public void testConnectRequestURLSameAsHost() throws Exception
    {
        final AtomicReference<String> resultRequestURL = new AtomicReference<>();
        final AtomicReference<String> resultRequestURI = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultRequestURL.set(request.getRequestURL().toString());
                resultRequestURI.set(request.getRequestURI());
            }
        });

        String rawResponse = _connector.getResponse(
            """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: myhost:9999\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.getRequestURL", resultRequestURL.get(), is("http://myhost:9999/"));
        assertThat("request.getRequestURI", resultRequestURI.get(), is("/"));
    }

    @Test
    public void testConnectRequestURLDifferentThanHost() throws Exception
    {
        final AtomicReference<String> resultRequestURL = new AtomicReference<>();
        final AtomicReference<String> resultRequestURI = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultRequestURL.set(request.getRequestURL().toString());
                resultRequestURI.set(request.getRequestURI());
            }
        });

        // per spec, "Host" is ignored if request-target is authority-form
        String rawResponse = _connector.getResponse(
            """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: otherhost:8888\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.getRequestURL", resultRequestURL.get(), is("http://myhost:9999/"));
        assertThat("request.getRequestURI", resultRequestURI.get(), is("/"));
    }

    @Test
    public void testAmbiguousURI() throws Exception
    {
        AtomicInteger count = new AtomicInteger();
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException
            {
                count.incrementAndGet();
                String requestURI = request.getRequestURI();
                String servletPath;
                String pathInfo;
                try
                {
                    servletPath = request.getServletPath();
                }
                catch (IllegalArgumentException iae)
                {
                    servletPath = iae.toString();
                }
                try
                {
                    pathInfo = request.getPathInfo();
                }
                catch (IllegalArgumentException iae)
                {
                    pathInfo = iae.toString();
                }

                resp.getOutputStream().println("requestURI=%s servletPath=%s pathInfo=%s".formatted(requestURI, servletPath, pathInfo));
            }
        });

        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        String rawRequest = """
            GET /test/foo%2fbar HTTP/1.1\r
            Host: localhost\r
            Connection: close\r
            \r
            """;
        String rawResponse = _connector.getResponse(rawRequest);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
        assertThat(count.get(), equalTo(0));

        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        rawResponse = _connector.getResponse(rawRequest);

        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("requestURI=/test/foo%2fbar"));
        assertThat(response.getContent(), containsString("servletPath=org.eclipse.jetty.http.HttpException$IllegalArgumentException: 400: Ambiguous URI encoding"));
        assertThat(response.getContent(), containsString("pathInfo=org.eclipse.jetty.http.HttpException$IllegalArgumentException: 400: Ambiguous URI encoding"));
        assertThat(count.get(), equalTo(1));

        _server.getContainedBeans(ServletHandler.class).iterator().next().setDecodeAmbiguousURIs(true);
        rawResponse = _connector.getResponse(rawRequest);

        response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("requestURI=/test/foo%2fbar"));
        assertThat(response.getContent(), containsString("servletPath= "));
        assertThat(response.getContent(), containsString("pathInfo=/test/foo/bar"));
        assertThat(count.get(), equalTo(2));
    }

    @Test
    public void testGetWithEncodedURI() throws Exception
    {
        final AtomicReference<String> resultRequestURI = new AtomicReference<>();
        final AtomicReference<String> resultPathInfo = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultRequestURI.set(request.getRequestURI());
                resultPathInfo.set(request.getPathInfo());
            }
        });

        String rawResponse = _connector.getResponse(
            """
                GET /test/path%20info/foo%2cbar HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.getRequestURI", resultRequestURI.get(), is("/test/path%20info/foo%2cbar"));
        assertThat("request.getPathInfo", resultPathInfo.get(), is("/test/path info/foo,bar"));
    }

    @Test
    public void testCachedServletCookies() throws Exception
    {
        final List<Cookie> cookieHistory = new ArrayList<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                Cookie[] cookies = request.getCookies();
                if (cookies != null)
                    cookieHistory.addAll(Arrays.asList(cookies));
            }
        });

        try (LocalConnector.LocalEndPoint connection = _connector.connect())
        {
            connection.addInput("""
                GET /one HTTP/1.1\r
                Host: myhost\r
                Cookie: name1=value1; name2=value2\r
                \r
                GET /two HTTP/1.1\r
                Host: myhost\r
                Cookie: name1=value1; name2=value2\r
                \r
                GET /three HTTP/1.1\r
                Host: myhost\r
                Cookie: name1=value1; name3=value3\r
                Connection: close\r
                \r
                """);

            assertThat(connection.getResponse(), containsString(" 200 OK"));
            assertThat(connection.getResponse(), containsString(" 200 OK"));
            assertThat(connection.getResponse(), containsString(" 200 OK"));
        }

        assertThat(cookieHistory.size(), is(6));
        assertThat(cookieHistory.stream().map(c -> c.getName() + "=" + c.getValue()).toList(), contains(
            "name1=value1",
            "name2=value2",
            "name1=value1",
            "name2=value2",
            "name1=value1",
            "name3=value3"
        ));

        assertThat(cookieHistory.get(0), sameInstance(cookieHistory.get(2)));
        assertThat(cookieHistory.get(2), not(sameInstance(cookieHistory.get(4))));
    }

    @Test
    public void testGetCharacterEncoding() throws Exception
    {
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp) throws IOException
            {
                // No character encoding specified
                request.getReader();
                // Try setting after read has been obtained
                request.setCharacterEncoding("ISO-8859-2");
                assertThat(request.getCharacterEncoding(), nullValue());
            }
        });

        String rawResponse = _connector.getResponse(
            """
                GET /test HTTP/1.1\r
                Host: host\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }
}
