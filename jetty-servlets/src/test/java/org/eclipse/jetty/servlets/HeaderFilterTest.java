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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletTester;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.http.HttpFieldsMatchers.containsHeaderValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.in;
import static org.hamcrest.Matchers.is;

public class HeaderFilterTest
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
    public void testHeaderFilterSet() throws Exception
    {
        FilterHolder holder = new FilterHolder(HeaderFilter.class);
        holder.setInitParameter("headerConfig", "set X-Frame-Options: DENY");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, containsHeaderValue("X-Frame-Options", "DENY"));
    }

    @Test
    public void testHeaderFilterAdd() throws Exception
    {
        FilterHolder holder = new FilterHolder(HeaderFilter.class);
        holder.setInitParameter("headerConfig", "add X-Frame-Options: DENY");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response, containsHeaderValue("X-Frame-Options", "DENY"));
    }

    @Test
    public void testHeaderFilterSetDate() throws Exception
    {
        FilterHolder holder = new FilterHolder(HeaderFilter.class);
        holder.setInitParameter("headerConfig", "setDate Expires: 100");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
        assertThat(response.toString(), HttpHeader.EXPIRES.asString(), is(in(response.getFieldNamesCollection())));
    }

    @Test
    public void testHeaderFilterAddDate() throws Exception
    {
        FilterHolder holder = new FilterHolder(HeaderFilter.class);
        holder.setInitParameter("headerConfig", "addDate Expires: 100");
        _tester.getContext().getServletHandler().addFilterWithMapping(holder, "/*", EnumSet.of(DispatcherType.REQUEST));

        HttpTester.Request request = HttpTester.newRequest();
        request.setMethod("GET");
        request.setVersion("HTTP/1.1");
        request.setHeader("Host", "localhost");
        request.setURI("/context/test/0");

        HttpTester.Response response = HttpTester.parseResponse(_tester.getResponses(request.generate()));
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
