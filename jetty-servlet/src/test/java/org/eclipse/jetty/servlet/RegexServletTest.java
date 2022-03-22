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

package org.eclipse.jetty.servlet;

import java.io.IOException;
import java.io.PrintWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.pathmap.PathMappings;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class RegexServletTest
{
    private Server _server;
    private LocalConnector _connector;
    private ServletContextHandler _servletContextHandler;

    @BeforeEach
    public void beforeEach()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);

        _servletContextHandler = new ServletContextHandler(_server, "/ctx");
        _servletContextHandler.setServletHandler(new ServletHandler()
        {
            @Override
            protected PathSpec asPathSpec(String pathSpec)
            {
                return PathMappings.asPathSpec(pathSpec);
            }
        });

        _server.setHandler(_servletContextHandler);
        _server.addConnector(_connector);
    }

    @Test
    public void testHello() throws Exception
    {
        _servletContextHandler.addServlet(new ServletHolder(new ServletContextHandlerTest.HelloServlet()), "^/[Hh]ello");
        _server.start();

        assertThat(_connector.getResponse("GET /ctx/hello HTTP/1.0\r\n\r\n"), containsString("Hello World"));
        assertThat(_connector.getResponse("GET /ctx/Hello HTTP/1.0\r\n\r\n"), containsString("Hello World"));
        assertThat(_connector.getResponse("GET /ctx/HELLO HTTP/1.0\r\n\r\n"), containsString(" 404"));
    }

    @Test
    public void testMapping() throws Exception
    {
        _servletContextHandler.addServlet(new ServletHolder(new TestServlet()), "^/test/.*$");
        _server.start();

        String response = _connector.getResponse("GET /ctx/test/info HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("contextPath='/ctx'"));
        assertThat(response, containsString("servletPath='/test/info'"));
        assertThat(response, containsString("pathInfo='null'"));
        assertThat(response, containsString("mapping.mappingMatch='null'"));
        assertThat(response, containsString("mapping.matchValue=''"));
        assertThat(response, containsString("mapping.pattern='^/test/.*$'"));
    }

    @Test
    public void testForward() throws Exception
    {
        _servletContextHandler.addServlet(new ServletHolder(new ForwardServlet()), "^/forward(/.*)?");
        _servletContextHandler.addServlet(new ServletHolder(new TestServlet()), "^/[Tt]est(/.*)?");
        _server.start();

        String response = _connector.getResponse("GET /ctx/forward/ignore HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("contextPath='/ctx'"));
        assertThat(response, containsString("servletPath='/Test/info'"));
        assertThat(response, containsString("pathInfo='null'"));
        assertThat(response, containsString("mapping.mappingMatch='null'"));
        assertThat(response, containsString("mapping.matchValue=''"));
        assertThat(response, containsString("mapping.pattern='^/[Tt]est(/.*)?'"));
    }

    @Test
    public void testInclude() throws Exception
    {
        _servletContextHandler.addServlet(new ServletHolder(new IncludeServlet()), "^/include$");
        _servletContextHandler.addServlet(new ServletHolder(new TestServlet()), "^/[Tt]est(/.*)?");
        _server.start();

        String response = _connector.getResponse("GET /ctx/include HTTP/1.0\r\n\r\n");
        assertThat(response, containsString(" 200 OK"));
        assertThat(response, containsString("contextPath='/ctx'"));
        assertThat(response, containsString("servletPath='/include'"));
        assertThat(response, containsString("pathInfo='null'"));
        assertThat(response, containsString("mapping.mappingMatch='null'"));
        assertThat(response, containsString("mapping.matchValue=''"));
        assertThat(response, containsString("mapping.pattern='^/include$'"));
    }

    static class TestServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setStatus(200);
            PrintWriter out = resp.getWriter();
            out.printf("contextPath='%s'%n", req.getContextPath());
            out.printf("servletPath='%s'%n", req.getServletPath());
            out.printf("pathInfo='%s'%n", req.getPathInfo());
            out.printf("mapping.mappingMatch='%s'%n", req.getHttpServletMapping().getMappingMatch());
            out.printf("mapping.matchValue='%s'%n", req.getHttpServletMapping().getMatchValue());
            out.printf("mapping.pattern='%s'%n", req.getHttpServletMapping().getPattern());
        }
    }

    static class ForwardServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            req.getServletContext().getRequestDispatcher("/Test/info").forward(req, resp);
        }
    }

    static class IncludeServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            req.getServletContext().getRequestDispatcher("/Test/info").include(req, resp);
        }
    }
}
