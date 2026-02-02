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

package org.eclipse.jetty.ee10.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpChannel;
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
    private HttpConfiguration httpConfig;

    public static class ReportViolationsFilter implements Filter
    {
        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException
        {
            if (request instanceof HttpServletRequest)
            {
                @SuppressWarnings("unchecked")
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
            if (headerNames.contains("Accept-Language"))
                out.printf("Locale = [%s]%n", req.getLocale());
        }
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setHttpCompliance(HttpCompliance.RFC2616_LEGACY);
        httpConfig.addComplianceViolationListener(new ComplianceViolation.CapturingListener());

        HttpConnectionFactory httpConnectionFactory = new HttpConnectionFactory(httpConfig);
        connector = new LocalConnector(server, null, null, null, -1, httpConnectionFactory);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.setWelcomeFiles(new String[]{"index.html", "index.jsp", "index.htm"});

        context.addServlet(DumpRequestHeadersServlet.class, "/dump/*");
        context.addFilter(ReportViolationsFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        server.setHandler(new Handler.Wrapper(context)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Request wrapped = new Request.Wrapper(request)
                {
                    @Override
                    public HttpFields getHeaders()
                    {
                        // Copy the headers to verify that
                        // the copy retains the HttpCompliance.
                        return HttpFields.build(super.getHeaders());
                    }
                };
                return super.handle(wrapped, response, callback);
            }
        });

        server.start();
    }

    @
        AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testQualityCsvWithBadQuotesAllowedByCompliance() throws Exception
    {
        // Note the bad Accept-Language value.
        String request = """
            GET /dump/ HTTP/1.1\r
            Host: local\r
            Accept: */*\r
            Accept-Language: 1'"6000\r
            Connection: close\r
            \r
            """;

        String response = connector.getResponse(request);
        assertThat("Response status", response, containsString("HTTP/1.1 200 OK"));
        assertThat("Response headers", response, not(containsString("X-Http-Violation-0")));
        assertThat("Response body", response, containsString("Locale = ["));
    }

    @Test
    public void testQualityCsvWithBadQuotesRejectedByCompliance() throws Exception
    {
        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            httpConfig.setHttpCompliance(HttpCompliance.RFC9110);

            // Note the bad Accept-Language value.
            String request = """
                GET /dump/ HTTP/1.1\r
                Host: local\r
                Accept: */*\r
                Accept-Language: 1'"6000\r
                Connection: close\r
                \r
                """;

            String response = connector.getResponse(request);
            assertThat("Response status", response, containsString("HTTP/1.1 400 Bad Request"));
            assertThat("Response body", response, containsString("Invalid quoted-quality"));
        }
    }

    @Test
    public void testNoColonHeaderMiddle() throws Exception
    {
        String request = """
            GET /dump/ HTTP/1.1\r
            Name\r
            Host: local\r
            Accept: */*\r
            Connection: close\r
            \r
            """;

        String response = connector.getResponse(request);
        assertThat("Response status", response, containsString("HTTP/1.1 200 OK"));
        assertThat("Response headers", response, containsString("X-Http-Violation-0: Fields must have a Colon"));
        assertThat("Response body", response, containsString("[Name] = []"));
    }

    @Test
    public void testNoColonHeaderEnd() throws Exception
    {
        String request = """
            GET /dump/ HTTP/1.1\r
            Host: local\r
            Connection: close\r
            Accept: */*\r
            Name\r
            \r
            """;

        String response = connector.getResponse(request);
        assertThat("Response status", response, containsString("HTTP/1.1 200"));
        assertThat("Response headers", response, containsString("X-Http-Violation-0: Fields must have a Colon"));
        assertThat("Response body", response, containsString("[Name] = []"));
    }

    @Test
    public void testFoldedHeader() throws Exception
    {
        String request = """
            GET /dump/ HTTP/1.1\r
            Host: local\r
            Name: Some\r
             Value\r
            Connection: close\r
            Accept: */*\r
            \r
            """;

        String response = connector.getResponse(request);
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
