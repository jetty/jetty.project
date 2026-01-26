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

package org.eclipse.jetty.ee9.servlet;

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
import org.eclipse.jetty.ee9.nested.HttpChannel;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class ComplianceViolations2616Test
{
    private Server server;
    private LocalConnector connector;
    private HttpConfiguration config;

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
            List<String> headerNames = new ArrayList<>();
            headerNames.addAll(Collections.list(req.getHeaderNames()));
            Collections.sort(headerNames);
            for (String name : headerNames)
            {
                out.printf("[%s] = [%s]%n", name, req.getHeader(name));
            }
            if (headerNames.contains("Accept-Language"))
                out.printf("Locale = [%s]%n", req.getLocale());
        }
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        config = new HttpConfiguration();
        config.setSendServerVersion(false);
        config.setHttpCompliance(HttpCompliance.RFC2616_LEGACY);
        config.addComplianceViolationListener(new ComplianceViolation.CapturingListener());

        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(config);
        connector = new LocalConnector(server, null, null, null, -1, httpConnectionFactory);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setWelcomeFiles(new String[]{"index.html", "index.jsp", "index.htm"});

        context.addServlet(DumpRequestHeadersServlet.class, "/dump/*");
        context.addFilter(ReportViolationsFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
        server.setHandler(new Handler.Wrapper(context.get())
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                request = new Request.Wrapper(request)
                {
                    @Override
                    public HttpFields getHeaders()
                    {
                        return HttpFields.build(super.getHeaders());
                    }
                };
                return getHandler().handle(request, response, callback);
            }
        });
        server.addConnector(connector);

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testQualityCsvWithBadQuotesAllowedByCompliance() throws Exception
    {
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /dump/ HTTP/1.1\r\n");
        req1.append("Host: local\r\n");
        req1.append("Accept: */*\r\n");
        req1.append("Accept-Language: 1'\"6000\r\n");
        req1.append("Connection: close\r\n");
        req1.append("\r\n");

        String response = connector.getResponse(req1.toString());
        assertThat("Response status", response, containsString("HTTP/1.1 200 OK"));
        assertThat("Response headers", response, not(containsString("X-Http-Violation-0n")));
        assertThat("Response body", response, containsString("Locale = ["));
    }

    @Test
    public void testQualityCsvWithBadQuotesRejectedByCompliance() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            config.setHttpCompliance(HttpCompliance.RFC9110);

            StringBuffer req1 = new StringBuffer();
            req1.append("GET /dump/ HTTP/1.1\r\n");
            req1.append("Host: local\r\n");
            req1.append("Accept: */*\r\n");
            req1.append("Accept-Language: 1'\"6000\r\n");
            req1.append("Connection: close\r\n");
            req1.append("\r\n");

            String response = connector.getResponse(req1.toString());
            assertThat("Response status", response, containsString("HTTP/1.1 500 Server Error"));
            assertThat("Response body", response, containsString("Bad Quotes in Token"));
        }
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
}
