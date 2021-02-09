//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.servlets;

import java.io.IOException;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;

public class IncludeExcludeBasedFilterTest
{
    private ServletTester _tester;

    @BeforeEach
    public void setUp() throws Exception
    {
        _tester = new ServletTester();
        _tester.setContextPath("/context");
        _tester.addServlet(NullServlet.class, "/test/*");

        _tester.start();
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        _tester.stop();
    }

    @Test
    public void testIncludeExcludeFilterIncludedPathMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedPaths", "^/test/0$");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludedPathNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedPaths", "^/nomatchtest$");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludedPathMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedPaths", "^/test/0$");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludedPathNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedPaths", "^/nomatchtest$");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterExcludeOverridesInclude() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedPaths", "^/test/0$");
        holder.setInitParameter("excludedPaths", "^/test/0$");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMethodMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedHttpMethods", "GET");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMethodNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedHttpMethods", "POST,PUT");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludeMethodMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedHttpMethods", "GET");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludeMethodNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedHttpMethods", "POST,PUT");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMimeTypeMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedMimeTypes", "application/json");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMimeTypeMatchWithQueryString() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedMimeTypes", "application/json");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json?some=value");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, containsHeaderValue("X-Custom-Value", "1"));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMimeTypeNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedMimeTypes", "application/xml");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterIncludeMimeTypeNoMatchNoExtension() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("includedMimeTypes", "application/json");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/abcdef");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludeMimeTypeMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedMimeTypes", "application/json");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, not(containsHeaderValue("X-Custom-Value", "1")));
    }

    @Test
    public void testIncludeExcludeFilterExcludeMimeTypeNoMatch() throws Exception
    {
        FilterHolder holder = new FilterHolder(MockIncludeExcludeFilter.class);
        holder.setInitParameter("excludedMimeTypes", "application/xml");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/json.json");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
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
