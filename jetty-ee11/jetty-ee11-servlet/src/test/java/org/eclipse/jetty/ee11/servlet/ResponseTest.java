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

package org.eclipse.jetty.ee11.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class ResponseTest
{
    private final HttpConfiguration _httpConfiguration = new HttpConfiguration();
    private Server _server;
    private LocalConnector _connector;

    public void startServer(ServletContextHandler contextHandler) throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server, new HttpConnectionFactory(_httpConfiguration));
        _server.addConnector(_connector);

        _server.setHandler(contextHandler);
        _server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(_server);
    }

    @Test
    public void testSimple() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentType("text/plain; charset=US-ASCII");
                response.getWriter().println("Hello");
            }
        };

        contextHandler.addServlet(servlet, "/servlet/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/servlet/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Type"), is("text/plain; charset=US-ASCII"));
        assertThat(response.getContent(), containsString("Hello"));
    }

    @Test
    public void testErrorWithMessage() throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setErrorHandler(new ErrorPageErrorHandler());
        contextHandler.setContextPath("/");
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                PrintWriter pw = response.getWriter();
                pw.println("THIS TEXT SHOULD NOT APPEAR");
                response.addHeader("header", "sendError_StringTest");
                response.addCookie(new Cookie("cookie1", "value1"));
                response.sendError(HttpServletResponse.SC_GONE, "The content is gone.");
            }
        };

        contextHandler.addServlet(servlet, "/error/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/error/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat(response.getStatus(), is(410));
        assertThat(response.get("Content-Type"), equalToIgnoringCase("text/html;charset=iso-8859-1"));
        assertThat(response.getContent(), containsString("The content is gone."));
    }

    public static Stream<Arguments> redirects()
    {
        List<Arguments> cases = new ArrayList<>();

        for (int code : new int[] {0, 307})
        {
            for (String location : new String[] {"somewhere/else", "/somewhere/else", "http://else/where" })
            {
                for (boolean relative : new boolean[] {true, false})
                {
                    for (boolean generate : new boolean[] {true, false})
                    {
                        for (String content : new String[] {null, "clear", "alternative text" })
                        {
                            cases.add(Arguments.of(code, location, relative, generate, content));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("redirects")
    public void testRedirect(int code, String location, boolean relative, boolean generate, String content) throws Exception
    {
        _httpConfiguration.setRelativeRedirectAllowed(relative);
        _httpConfiguration.setGenerateRedirectBody(generate);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/ctx");
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (code > 0)
                {
                    if (content == null)
                    {
                        response.getOutputStream().write("oops".getBytes(StandardCharsets.UTF_8));
                        response.sendRedirect(location, code);
                    }
                    else if ("clear".equals(content))
                    {
                        response.getOutputStream().write("oops".getBytes(StandardCharsets.UTF_8));
                        response.sendRedirect(location, code, true);
                    }
                    else
                    {
                        response.setContentType(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                        response.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
                        response.sendRedirect(location, code, false);
                    }
                }
                else
                {
                    if (content == null)
                    {
                        response.getOutputStream().write("oops".getBytes(StandardCharsets.UTF_8));
                        response.sendRedirect(location);
                    }
                    else if ("clear".equals(content))
                    {
                        response.getOutputStream().write("oops".getBytes(StandardCharsets.UTF_8));
                        response.sendRedirect(location, true);
                    }
                    else
                    {
                        response.setContentType(MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                        response.getOutputStream().write(content.getBytes(StandardCharsets.UTF_8));
                        response.sendRedirect(location, false);
                    }
                }
            }
        };

        contextHandler.addServlet(servlet, "/servlet/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/ctx/servlet/test");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat(response.getStatus(), is(code == 0 ? HttpStatus.FOUND_302 : code));

        String destination = location;
        if (relative)
        {
            if (!location.startsWith("/") && !location.startsWith("http:/"))
                destination = "/ctx/servlet/" + location;
        }
        else
        {
            if (location.startsWith("/"))
                destination = "http://test" + location;
            else if (!location.startsWith("http:/"))
                destination = "http://test/ctx/servlet/" + location;
        }

        HttpField to = response.getField(HttpHeader.LOCATION);
        assertThat(to, notNullValue());
        assertThat(to.getValue(), is(destination));

        String expected = content;
        if ("clear".equals(expected))
            expected = null;

        String actual = response.getContent();

        if (expected == null)
        {
            if (generate)
            {
                assertThat(response.get(HttpHeader.CONTENT_TYPE), containsString("text/html"));
                assertThat(actual, containsString("If you are not redirected, <a href=\"%s\">click here</a>".formatted(destination)));
                assertThat(actual, not(containsString("oops")));
            }
            else
            {
                assertThat(response.get().get(HttpHeader.CONTENT_TYPE), nullValue());
                assertThat(actual, emptyString());
            }
        }
        else
        {
            assertThat(response.get().get(HttpHeader.CONTENT_TYPE), notNullValue());
            assertThat(actual, not(containsString("oops")));
            assertThat(actual, containsString(expected));
        }
    }

    @Test
    public void testSetContentLengthAfterCommit() throws Exception
    {
        testActionAfterCommit((request, response) ->
        {
            response.setContentLength(20);
            assertThat(response.getHeader("Content-Length"), is("5"));
        });
    }

    @Test
    public void testSetHeaderAfterCommit() throws Exception
    {
        testActionAfterCommit((request, response) ->
        {
            response.setHeader("foo", "bar");
            assertThat(response.getHeader("foo"), nullValue());
        });
    }

    @Test
    public void testAddHeaderAfterCommit() throws Exception
    {
        testActionAfterCommit((request, response) ->
        {
            response.addHeader("foo", "bar");
            assertThat(response.getHeader("foo"), nullValue());
        });
    }

    @Test
    public void testAddDateHeaderAfterCommit() throws Exception
    {
        testActionAfterCommit((req, resp) ->
        {
            resp.addDateHeader("foo-date", System.currentTimeMillis());
            assertThat(resp.getHeader("foo-date"), nullValue());
        });
    }

    @Test
    public void testSetDateHeaderAfterCommit() throws Exception
    {
        testActionAfterCommit((req, resp) ->
        {
            resp.setDateHeader("foo-date", System.currentTimeMillis());
            assertThat(resp.getHeader("foo-date"), nullValue());
        });
    }

    @Test
    public void testSetStatusAfterCommit() throws Exception
    {
        testActionAfterCommit((req, resp) ->
        {
            resp.setStatus(HttpStatus.FORBIDDEN_403);
            assertThat(resp.getStatus(), is(HttpStatus.OK_200));
        });
    }

    private void testActionAfterCommit(BiConsumer<HttpServletRequest, HttpServletResponse> action)
        throws Exception
    {
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentType("text/plain; charset=US-ASCII");
                response.getWriter().println("Hello");
                response.getWriter().flush();
                assertThat(response.isCommitted(), is(Boolean.TRUE));
                action.accept(request, response);
            }
        };

        contextHandler.addServlet(servlet, "/servlet/*");
        startServer(contextHandler);

        HttpTester.Request request = new HttpTester.Request();
        request.setMethod("GET");
        request.setURI("/servlet/");
        request.setVersion(HttpVersion.HTTP_1_1);
        request.setHeader("Connection", "close");
        request.setHeader("Host", "test");

        ByteBuffer responseBuffer = _connector.getResponse(request.generate());
        HttpTester.Response response = HttpTester.parseResponse(responseBuffer);

        assertThat(response.getStatus(), is(200));
        assertThat(response.get("Content-Type"), is("text/plain; charset=US-ASCII"));
        assertThat(response.getContent(), containsString("Hello"));
    }
}
