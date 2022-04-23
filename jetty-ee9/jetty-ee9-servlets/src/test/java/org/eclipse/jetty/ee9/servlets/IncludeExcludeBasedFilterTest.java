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

package org.eclipse.jetty.ee9.servlets;

import java.io.IOException;
import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.FilterHolder;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.tools.matchers.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;

public class IncludeExcludeBasedFilterTest
{
    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _context;

    @BeforeEach
    public void setUp() throws Exception
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _server.addConnector(_connector);
        _context = new ServletContextHandler(_server, "/context");
        _context.addServlet(NullServlet.class, "/test/*");
        _server.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(_server);
    }

    @Test
    public void testIncludeExcludeFilterIncludedPathMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedPaths", "^/test/0$");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludedPathNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedPaths", "^/nomatchtest$");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludedPathMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedPaths", "^/test/0$");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludedPathNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedPaths", "^/nomatchtest$");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterExcludeOverridesInclude() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedPaths", "^/test/0$");
        holder.setInitParameter("excludedPaths", "^/test/0$");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMethodMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedHttpMethods", "GET");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMethodNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedHttpMethods", "POST,PUT");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludeMethodMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedHttpMethods", "GET");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludeMethodNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedHttpMethods", "POST,PUT");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMimeTypeMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedMimeTypes", "application/json");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMimeTypeMatchWithQueryString() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedMimeTypes", "application/json");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json?some=value");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMimeTypeNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedMimeTypes", "application/xml");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMimeTypeNoMatchNoExtension() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedMimeTypes", "application/json");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/abcdef");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludeMimeTypeMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedMimeTypes", "application/json");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludeMimeTypeNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedMimeTypes", "application/xml");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    public static class MockIncludeExcludeFilter extends IncludeExcludeBasedFilter
    {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            HttpServletRequest httpRequest = (HttpServletRequest)request;
            HttpServletResponse httpResponse = (HttpServletResponse)response;

            if (super.shouldFilter(httpRequest, httpResponse))
            {
                httpResponse.setHeader("X-Custom-Value", "1");
            }

            chain.doFilter(request, response);
        }
    }

    public static class NullServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(HttpStatus.NO_CONTENT_204);
            resp.flushBuffer();
        }
    }
}
