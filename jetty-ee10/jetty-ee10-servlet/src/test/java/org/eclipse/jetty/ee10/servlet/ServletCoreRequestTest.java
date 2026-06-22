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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.ComplianceViolation;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class ServletCoreRequestTest
{
    private Server _server;
    private LocalConnector _connector;
    private TestViolationListener _violationListener;

    public void startServer(HttpCompliance httpCompliance, HttpServlet servlet) throws Exception
    {
        _server = new Server();

        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setHttpCompliance(httpCompliance);
        _violationListener = new TestViolationListener();
        httpConfiguration.addComplianceViolationListener(_violationListener);

        _connector = new LocalConnector(_server, new HttpConnectionFactory(httpConfiguration));
        _server.addConnector(_connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(servlet, "/");
        _server.setHandler(context);

        _server.start();
    }

    @AfterEach
    public void stopServer()
    {
        LifeCycle.stop(_server);
    }

    private static class TestViolationListener implements ComplianceViolation.Listener
    {
        List<ComplianceViolation.Event> events = new ArrayList<>();

        @Override
        public void onComplianceViolation(ComplianceViolation.Event event)
        {
            events.add(event);
        }
    }

    public enum CsvType
    {
        QUOTED_CSV,
        QUOTED_QUALITY_CSV
    }

    public static Stream<Arguments> cases()
    {
        return Stream.of(
            arguments(CsvType.QUOTED_CSV),
            arguments(CsvType.QUOTED_QUALITY_CSV)
        );
    }

    @ParameterizedTest()
    @MethodSource("cases")
    public void testHttpCompliance(CsvType csvType) throws Exception
    {
        // The default RFC9110 mode does not allow whitespace, however RFC7230 does.
        String value = "token;q =0.5";
        startServer(HttpCompliance.RFC7230, new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response)
            {
                // Wrap the HttpServletResponse to ServletCoreRequest to verify that
                // the HttpCompliance and ViolationListener are transferred to the wrapped Request.
                Request coreRequest = ServletCoreRequest.wrap(request);
                HttpFields fields = coreRequest.getHeaders();
                if (csvType == CsvType.QUOTED_CSV)
                    fields.newQuotedCSV(false).addValue(value);
                else
                    fields.newQuotedQualityCSV(null).addValue(value);
            }
        });

        String rawRequest = """
            GET / HTTP/1.1\r
            Host: local\r
            Connection: close\r
            \r
            """;

        // Using RFC7230 should allow this whitespace violation and return a 200 response.
        HttpTester.Response response = HttpTester.parseResponse(_connector.getResponse(rawRequest));
        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        // We should have been notified about the violation in the listener.
        assertThat(_violationListener.events, hasSize(1));
        ComplianceViolation.Event event = _violationListener.events.get(0);
        assertThat(event.mode(), equalTo(HttpCompliance.RFC7230));
        assertThat(event.violation(), equalTo(HttpCompliance.Violation.WHITESPACE_IN_PARAMETER));
        assertThat(event.allowed(), equalTo(true));
    }
}