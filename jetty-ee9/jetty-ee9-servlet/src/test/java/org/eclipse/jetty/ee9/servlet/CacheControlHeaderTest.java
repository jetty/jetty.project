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

package org.eclipse.jetty.ee9.servlet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class CacheControlHeaderTest
{
    private Server server;
    private LocalConnector connector;

    public static class SimpleResponseWrapper extends HttpServletResponseWrapper
    {
        public SimpleResponseWrapper(HttpServletResponse response)
        {
            super(response);
        }
    }

    public static class ForceCacheControlFilter implements Filter
    {
        private boolean forceWrapper;

        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
            forceWrapper = Boolean.parseBoolean(filterConfig.getInitParameter("FORCE_WRAPPER"));
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            HttpServletResponse httpResponse = (HttpServletResponse)response;
            httpResponse.setHeader(HttpHeader.CACHE_CONTROL.asString(), "max-age=0,private");
            if (forceWrapper)
            {
                chain.doFilter(request, new SimpleResponseWrapper((HttpServletResponse)response));
            }
            else
            {
                chain.doFilter(request, response);
            }
        }

        @Override
        public void destroy()
        {
        }
    }

    public void startServer(boolean forceFilter, boolean forceWrapping) throws Exception
    {
        server = new Server();

        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(new HttpConfiguration());
        connector = new LocalConnector(server, null, null, null, -1, httpConnectionFactory);

        ServletContextHandler context = new ServletContextHandler();

        ServletHolder servletHolder = new ServletHolder();
        servletHolder.setServlet(new DefaultServlet());
        servletHolder.setInitParameter("cacheControl", "max-age=3600,public");
        Path resBase = MavenTestingUtils.getTargetPath("test-classes/contextResources");
        servletHolder.setInitParameter("resourceBase", resBase.toFile().toURI().toASCIIString());
        context.addServlet(servletHolder, "/*");
        if (forceFilter)
        {
            FilterHolder filterHolder = context.addFilter(ForceCacheControlFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
            filterHolder.setInitParameter("FORCE_WRAPPER", Boolean.toString(forceWrapping));
        }
        server.setHandler(context);
        server.addConnector(connector);

        server.start();
    }

    public void stopServer() throws Exception
    {
        if (server != null && server.isRunning())
        {
            server.stop();
        }
    }

    @Test
    public void testCacheControlFilterOverride() throws Exception
    {
        try
        {
            startServer(true, false);
            StringBuffer req1 = new StringBuffer();
            req1.append("GET /content.txt HTTP/1.1\r\n");
            req1.append("Host: local\r\n");
            req1.append("Accept: */*\r\n");
            req1.append("Connection: close\r\n");
            req1.append("\r\n");

            String response = connector.getResponse(req1.toString());
            assertThat("Response status",
                       response,
                       containsString("HTTP/1.1 200 OK"));
            assertThat("Response headers",
                        response,
                        containsString(HttpHeader.CACHE_CONTROL.asString() + ": max-age=0,private"));
        } 
        finally
        {
            stopServer();
        }
    }

    @Test
    @Disabled // TODO
    public void testCacheControlFilterOverrideWithWrapper() throws Exception
    {
        try
        {
            startServer(true, true);
            StringBuffer req1 = new StringBuffer();
            req1.append("GET /content.txt HTTP/1.1\r\n");
            req1.append("Host: local\r\n");
            req1.append("Accept: */*\r\n");
            req1.append("Connection: close\r\n");
            req1.append("\r\n");

            String response = connector.getResponse(req1.toString());
            assertThat("Response status",
                       response,
                       containsString("HTTP/1.1 200 OK"));
            assertThat("Response headers",
                       response,
                       containsString(HttpHeader.CACHE_CONTROL.asString() + ": max-age=0,private"));
        }
        finally
        {
            stopServer();
        }
    }

    @Test
    public void testCacheControlDefaultServlet() throws Exception
    {
        try
        {
            startServer(false, false);
            StringBuffer req1 = new StringBuffer();
            req1.append("GET /content.txt HTTP/1.1\r\n");
            req1.append("Host: local\r\n");
            req1.append("Accept: */*\r\n");
            req1.append("Connection: close\r\n");
            req1.append("\r\n");

            String response = connector.getResponse(req1.toString());
            assertThat("Response status",
                       response,
                       containsString("HTTP/1.1 200 OK"));
            assertThat("Response headers",
                       response,
                       containsString(HttpHeader.CACHE_CONTROL.asString() + ": max-age=3600,public"));
        } 
        finally
        {
            stopServer();
        }
    }

}
