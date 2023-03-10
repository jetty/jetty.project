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
import java.util.EnumSet;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.URIUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;

public class EncodedURITest
{
    private Server _server;
    private LocalConnector _connector;
    private ContextHandlerCollection _contextCollection;
    private ServletContextHandler _context0;
    private ServletContextHandler _context1;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        _server.addConnector(_connector);

        _contextCollection = new ContextHandlerCollection();
        _server.setHandler(_contextCollection);

        _context0 = new ServletContextHandler();
        _context0.setContextPath("/context path");
        _contextCollection.addHandler(_context0);
        _context0.addFilter(AsyncFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        _context0.addServlet(TestServlet.class, "/test servlet/*");
        _context0.addServlet(AsyncServlet.class, "/async servlet/*");

        _context1 = new ServletContextHandler();
        _context1.setContextPath("/redirecting context");
        _contextCollection.addHandler(_context1);

        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testTestServlet() throws Exception
    {
        String response = _connector.getResponse("GET /c%6Fntext%20path/test%20servlet/path%20info HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/c%6Fntext%20path/test%20servlet/path%20info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path info"));
    }

    @Test
    public void testAsyncFilterTestServlet() throws Exception
    {
        String response = _connector.getResponse("GET /context%20path/test%20servlet/path%20info?async=true HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context%20path/test%20servlet/path%20info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path info"));
    }

    @Test
    public void testAsyncFilterWrapTestServlet() throws Exception
    {
        String response = _connector.getResponse("GET /context%20path/test%20servlet/path%20info?async=true&wrap=true HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context%20path/test%20servlet/path%20info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path info"));
    }

    @Test
    public void testAsyncServletTestServlet() throws Exception
    {
        String response = _connector.getResponse("GET /context%20path/async%20servlet/path%20info HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context%20path/test%20servlet/path%20info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path info"));
    }

    @Test // TODO Need to check spec if encoded async dispatch is really supported
    @Disabled
    public void testAsyncServletTestServletEncoded() throws Exception
    {
        String response = _connector.getResponse("GET /context%20path/async%20servlet/path%20info?encode=true HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context%20path/test%20servlet/path%2520info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path info"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%2F", "%3F"})
    public void testCanonicallyEncodedUris(String separator) throws Exception
    {
        _server.stop();
        ServletContextHandler context2 = new ServletContextHandler();
        context2.setContextPath("/context_path".replace("_", separator));
        _contextCollection.addHandler(context2);
        context2.addServlet(TestServlet.class, URIUtil.decodePath("/test_servlet/*".replace("_", separator)));
        _connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        _server.start();

        String response = _connector.getResponse("GET /context_path/test_servlet/path_info HTTP/1.0\n\n".replace("_", separator));
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context_path/test_servlet/path_info".replace("_", separator)));
        assertThat(response, Matchers.containsString("contextPath=/context_path".replace("_", separator)));
        if ("%2F".equals(separator))
        {
            assertThat(response, Matchers.containsString("servletPath=org.eclipse.jetty.http.HttpException$IllegalArgumentException: 400: Ambiguous URI encoding"));
            assertThat(response, Matchers.containsString("pathInfo=org.eclipse.jetty.http.HttpException$IllegalArgumentException: 400: Ambiguous URI encoding"));
        }
        else
        {
            assertThat(response, Matchers.containsString("servletPath=/test_servlet".replace("_", "?")));
            assertThat(response, Matchers.containsString("pathInfo=/path_info".replace("_", "?")));
        }
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/plain");
            response.getWriter().println("requestURI=" + request.getRequestURI());
            response.getWriter().println("contextPath=" + request.getContextPath());
            try
            {
                response.getWriter().println("servletPath=" + request.getServletPath());
            }
            catch (Throwable e)
            {
                response.getWriter().println("servletPath=" + e);
            }
            try
            {
                response.getWriter().println("pathInfo=" + request.getPathInfo());
            }
            catch (Throwable e)
            {
                response.getWriter().println("pathInfo=" + e);
            }
        }
    }

    public static class AsyncServlet extends HttpServlet
    {
        @Override
        public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            AsyncContext async = Boolean.parseBoolean(request.getParameter("wrap"))
                ? request.startAsync(request, response)
                : request.startAsync();

            if (Boolean.parseBoolean(request.getParameter("encode")))
                async.dispatch("/test%20servlet" + URIUtil.encodePath(request.getPathInfo()));
            else
                async.dispatch("/test servlet" + request.getPathInfo());
            return;
        }
    }

    public static class AsyncFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (Boolean.parseBoolean(request.getParameter("async")) && !Boolean.parseBoolean((String)request.getAttribute("async")))
            {
                request.setAttribute("async", "true");
                AsyncContext async = Boolean.parseBoolean(request.getParameter("wrap"))
                    ? request.startAsync(request, response)
                    : request.startAsync();
                async.dispatch();
                return;
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }
}
