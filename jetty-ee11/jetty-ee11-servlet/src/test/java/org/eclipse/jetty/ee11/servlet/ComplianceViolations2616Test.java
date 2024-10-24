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
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

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
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class ComplianceViolations2616Test
{
    private static Server server;
    private static LocalConnector connector;

    public static class ReportViolationsFilter implements Filter
    {
        @Override
        public void init(FilterConfig filterConfig) throws ServletException
        {
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (request instanceof HttpServletRequest)
            {
                List<ComplianceViolation.Event> violations = (List<ComplianceViolation.Event>)request.getAttribute("org.eclipse.jetty.http.compliance.violations");
                if (violations != null)
                {
                    HttpServletResponse httpResponse = (HttpServletResponse)response;
                    int i = 0;
                    for (ComplianceViolation.Event event : violations)
                    {
                        httpResponse.setHeader("X-Http-Violation-" + (i++), event.toString());
                    }
                }
            }
            chain.doFilter(request, response);
        }

        @Override
        public void destroy()
        {
        }
    }

    public static class DumpRequestHeadersServlet extends HttpServlet
    {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            resp.setContentType("text/plain");
            PrintWriter out = resp.getWriter();
            out.printf("%s %s%s%s\n", req.getMethod(), req.getContextPath(), req.getServletPath(), req.getPathInfo());
            List<String> headerNames = new ArrayList<>(Collections.list(req.getHeaderNames()));
            Collections.sort(headerNames);
            for (String name : headerNames)
            {
                out.printf("[%s] = [%s]%n", name, req.getHeader(name));
            }
        }
    }

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new Server();

        HttpConfiguration config = new HttpConfiguration();
        config.setSendServerVersion(false);
        config.setHttpCompliance(HttpCompliance.RFC2616_LEGACY);
        config.addComplianceViolationListener(new ComplianceViolation.LoggingListener());
        config.addComplianceViolationListener(new ComplianceViolation.CapturingListener());

        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(config);
        connector = new LocalConnector(server, null, null, null, -1, httpConnectionFactory);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setWelcomeFiles(new String[]{"index.html", "index.jsp", "index.htm"});

        context.addServlet(DumpRequestHeadersServlet.class, "/dump/*");
        context.addFilter(ReportViolationsFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        server.setHandler(context);
        server.addConnector(connector);

        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testNoColonHeaderMiddle() throws Exception
    {
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /dump/ HTTP/1.1\r\n");
        req1.append("Name\r\n");
        req1.append("Host: local\r\n");
        req1.append("Accept: */*\r\n");
        req1.append("Connection: close\r\n");
        req1.append("\r\n");

        String response = connector.getResponse(req1.toString());
        assertThat("Response status", response, containsString("HTTP/1.1 200 OK"));
        assertThat("Response headers", response, containsString("X-Http-Violation-0: Fields must have a Colon"));
        assertThat("Response body", response, containsString("[Name] = []"));
    }

    @Test
    public void testNoColonHeaderEnd() throws Exception
    {
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /dump/ HTTP/1.1\r\n");
        req1.append("Host: local\r\n");
        req1.append("Connection: close\r\n");
        req1.append("Accept: */*\r\n");
        req1.append("Name\r\n");
        req1.append("\r\n");

        String response = connector.getResponse(req1.toString());
        assertThat("Response status", response, containsString("HTTP/1.1 200"));
        assertThat("Response headers", response, containsString("X-Http-Violation-0: Fields must have a Colon"));
        assertThat("Response body", response, containsString("[Name] = []"));
    }

    @Test
    public void testFoldedHeader() throws Exception
    {
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /dump/ HTTP/1.1\r\n");
        req1.append("Host: local\r\n");
        req1.append("Name: Some\r\n");
        req1.append(" Value\r\n");
        req1.append("Connection: close\r\n");
        req1.append("Accept: */*\r\n");
        req1.append("\r\n");

        String response = connector.getResponse(req1.toString());
        assertThat("Response status", response, containsString("HTTP/1.1 200"));
        assertThat("Response headers", response, containsString("X-Http-Violation-0: Line Folding not supported"));
        assertThat("Response body", response, containsString("[Name] = [Some Value]"));
    }

    @Test
    public void testAmbiguousSlash() throws Exception
    {
        String request = """
            GET /dump/foo//bar HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """;

        String response = connector.getResponse(request);
        assertThat(response, containsString("HTTP/1.1 400 Bad"));

        connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986.with("test", UriCompliance.Violation.AMBIGUOUS_EMPTY_SEGMENT));
        server.getContainedBeans(ServletHandler.class).stream().findFirst().get().setDecodeAmbiguousURIs(true);

        response = connector.getResponse(request);
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("GET /dump/foo//bar"));
    }
}
