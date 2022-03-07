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

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ServletWrapperTest
{
    private Server server;
    private LocalConnector localConnector;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        localConnector = new LocalConnector(server);
        server.addConnector(localConnector);

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        ServletHolder servletHolder = context.addServlet(HelloServlet.class, "/hello");
        servletHolder.setAsyncSupported(false);
        FilterHolder filterHolder = context.addFilter(WrapFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        filterHolder.setAsyncSupported(true);

        server.setHandler(new Handler.Collection(context, new DefaultHandler()));
        server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testWrapper() throws Exception
    {
        StringBuilder req = new StringBuilder();
        req.append("GET /hello HTTP/1.1\r\n");
        req.append("Host: local\r\n");
        req.append("Connection: close\r\n");
        req.append("\r\n");

        String rawResponse = localConnector.getResponse(req.toString());
        HttpTester.Response resp = HttpTester.parseResponse(rawResponse);
        assertThat("Response.status", resp.getStatus(), is(200));
    }

    public static class HelloServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            resp.setCharacterEncoding("utf-8");
            resp.getWriter().println("Hello Test");
        }
    }

    public static class WrapFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
            // ignore
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (!(request instanceof HttpServletRequest) || !(response instanceof HttpServletResponse))
            {
                chain.doFilter(request, response);
                return;
            }

            HttpServletRequest httpRequest = (HttpServletRequest)request;
            HttpServletResponse httpResponse = (HttpServletResponse)response;

            httpRequest = new NoopRequestWrapper(httpRequest);
            chain.doFilter(httpRequest, httpResponse);
        }

        @Override
        public void destroy()
        {
            // ignore
        }
    }

    public static class NoopRequestWrapper extends HttpServletRequestWrapper
    {
        public NoopRequestWrapper(HttpServletRequest request)
        {
            super(request);
        }
    }
}
