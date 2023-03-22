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

package org.eclipse.jetty.ee9.servlets;

import java.io.IOException;
import java.util.EnumSet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee9.servlet.FilterHolder;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.http.HttpHeader;
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
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;

public class HeaderFilterTest
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
    public void testHeaderFilterSet() throws Exception
    {
        FilterHolder holder = new FilterHolder(HeaderFilter.class);
        holder.setInitParameter("headerConfig", "set X-Frame-Options: DENY");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Frame-Options", "DENY"));
    }

    @Test
    public void testHeaderFilterAdd() throws Exception
    {
        FilterHolder holder = new FilterHolder(HeaderFilter.class);
        holder.setInitParameter("headerConfig", "add X-Frame-Options: DENY");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response, containsHeaderValue("X-Frame-Options", "DENY"));
    }

    @Test
    public void testHeaderFilterSetDate() throws Exception
    {
        FilterHolder holder = new FilterHolder(HeaderFilter.class);
        holder.setInitParameter("headerConfig", "setDate Expires: 100");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response.toString(), HttpHeader.EXPIRES.asString(), is(in(response.getFieldNamesCollection())));
    }

    @Test
    public void testHeaderFilterAddDate() throws Exception
    {
        FilterHolder holder = new FilterHolder(HeaderFilter.class);
        holder.setInitParameter("headerConfig", "addDate Expires: 100");
        _context.getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(request.generate()));
        assertThat(response.toString(), HttpHeader.EXPIRES.asString(), is(in(response.getFieldNamesCollection())));
    }

    public static class NullServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(HttpStatus.NO_CONTENT_204);
        }
    }
}
