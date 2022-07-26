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
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        assertThat(response, Matchers.containsString("servletPath=/test%20servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path%20info"));
    }

    @Test
    public void testAsyncFilterTestServlet() throws Exception
    {
        String response = _connector.getResponse("GET /context%20path/test%20servlet/path%20info?async=true HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context%20path/test%20servlet/path%20info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test%20servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path%20info"));
    }

    @Test
    public void testAsyncFilterWrapTestServlet() throws Exception
    {
        String response = _connector.getResponse("GET /context%20path/test%20servlet/path%20info?async=true&wrap=true HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context%20path/test%20servlet/path%20info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test%20servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path%20info"));
    }

    @Test
    public void testAsyncServletTestServlet() throws Exception
    {
        String response = _connector.getResponse("GET /context%20path/async%20servlet/path%20info HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context%20path/test%20servlet/path%20info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test%20servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path%20info"));
    }

    @Test
    public void testAsyncServletTestServletEncoded() throws Exception
    {
        String response = _connector.getResponse("GET /context%20path/async%20servlet/path%20info?encode=true HTTP/1.0\n\n");
        assertThat(response, startsWith("HTTP/1.1 200 "));
        assertThat(response, Matchers.containsString("requestURI=/context%20path/test%20servlet/path%20info"));
        assertThat(response, Matchers.containsString("contextPath=/context%20path"));
        assertThat(response, Matchers.containsString("servletPath=/test%20servlet"));
        assertThat(response, Matchers.containsString("pathInfo=/path%20info"));
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/plain");
            response.getWriter().println("requestURI=" + request.getRequestURI());
            response.getWriter().println("contextPath=" + request.getContextPath());
            response.getWriter().println("servletPath=" + request.getServletPath());
            response.getWriter().println("pathInfo=" + request.getPathInfo());
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
                async.dispatch("/test%20servlet" + request.getPathInfo());
            else
                async.dispatch("/test servlet/path info" + request.getPathInfo());
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
