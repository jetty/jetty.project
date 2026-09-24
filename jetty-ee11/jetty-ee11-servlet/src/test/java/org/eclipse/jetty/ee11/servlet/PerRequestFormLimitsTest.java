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
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class PerRequestFormLimitsTest
{
    private Server _server;
    private ServerConnector _connector;

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    public static Stream<Arguments> formLimitsProvider()
    {
        // Arguments are (maxFormFields, maxFormLength, expected status), applied to a form of 27 bytes
        // with 2 fields. A value of -1 leaves that request attribute unset, so the context default applies.
        return Stream.of(
            Arguments.of(-1, -1, HttpStatus.OK_200),
            Arguments.of(100, 100, HttpStatus.OK_200),
            Arguments.of(2, -1, HttpStatus.OK_200),
            Arguments.of(1, -1, HttpStatus.BAD_REQUEST_400),
            Arguments.of(0, -1, HttpStatus.BAD_REQUEST_400),
            Arguments.of(-1, 27, HttpStatus.OK_200),
            Arguments.of(-1, 26, HttpStatus.BAD_REQUEST_400),
            Arguments.of(-1, 0, HttpStatus.BAD_REQUEST_400)
            );
    }

    @ParameterizedTest
    @MethodSource("formLimitsProvider")
    public void perRequestFormLimitsTest(int maxFormFields, int maxFormLength, int expectedStatusCode) throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        _server.setHandler(servletContextHandler);
        servletContextHandler.addFilter(new HttpFilter()
        {
            @Override
            public void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException
            {
                String maxFieldsAttribute = req.getHeader(FormFields.MAX_FIELDS_ATTRIBUTE);
                if (maxFieldsAttribute != null)
                    req.setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, maxFieldsAttribute);

                String maxLengthAttribute = req.getHeader(FormFields.MAX_LENGTH_ATTRIBUTE);
                if (maxLengthAttribute != null)
                    req.setAttribute(FormFields.MAX_LENGTH_ATTRIBUTE, maxLengthAttribute);

                chain.doFilter(req, res);
            }
        }, "/*", EnumSet.allOf(DispatcherType.class));

        _server.start();

        HttpTester.Response response = sendForm(maxFormFields, maxFormLength);
        assertThat(response.getStatus(), is(expectedStatusCode));
    }

    @Test
    public void testRequestAttributeTightensContextKeysLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.setMaxFormKeys(10);
        _server.setHandler(servletContextHandler);
        addFormLimitsFilter(servletContextHandler, FormFields.MAX_FIELDS_ATTRIBUTE, 1);
        _server.start();

        assertThat(sendForm().getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testRequestAttributeRelaxesContextKeysLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.setMaxFormKeys(1);
        _server.setHandler(servletContextHandler);
        addFormLimitsFilter(servletContextHandler, FormFields.MAX_FIELDS_ATTRIBUTE, 10);
        _server.start();

        HttpTester.Response response = sendForm();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("param1"));
        assertThat(response.getContent(), containsString("param2"));
    }

    @Test
    public void testRequestAttributeTightensContextLengthLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.setMaxFormContentSize(100);
        _server.setHandler(servletContextHandler);
        addFormLimitsFilter(servletContextHandler, FormFields.MAX_LENGTH_ATTRIBUTE, 26);
        _server.start();

        assertThat(sendForm().getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testRequestAttributeRelaxesContextLengthLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.setMaxFormContentSize(26);
        _server.setHandler(servletContextHandler);
        addFormLimitsFilter(servletContextHandler, FormFields.MAX_LENGTH_ATTRIBUTE, 27);
        _server.start();

        HttpTester.Response response = sendForm();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("param1"));
        assertThat(response.getContent(), containsString("param2"));
    }

    @Test
    public void testContextAttributeLimitApplies() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.getContext().setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, 1);
        _server.setHandler(servletContextHandler);
        _server.start();

        assertThat(sendForm().getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testRequestAttributeOverridesContextAttributeLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.getContext().setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, 1);
        _server.setHandler(servletContextHandler);
        addFormLimitsFilter(servletContextHandler, FormFields.MAX_FIELDS_ATTRIBUTE, 10);
        _server.start();

        HttpTester.Response response = sendForm();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("param1"));
        assertThat(response.getContent(), containsString("param2"));
    }

    private ServletContextHandler newServletContext()
    {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.addServlet(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getWriter().print(req.getParameterMap().keySet());
            }
        }, "/");
        return servletContextHandler;
    }

    private void addFormLimitsFilter(ServletContextHandler servletContextHandler, String attribute, int value)
    {
        servletContextHandler.addFilter(new HttpFilter()
        {
            @Override
            public void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException
            {
                req.setAttribute(attribute, value);
                chain.doFilter(req, res);
            }
        }, "/*", EnumSet.allOf(DispatcherType.class));
    }

    private HttpTester.Response sendForm() throws Exception
    {
        return sendForm(-1, -1);
    }

    private HttpTester.Response sendForm(int maxFormFields, int maxFormLength) throws Exception
    {
        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            StringBuilder request = new StringBuilder();
            request.append("""
                POST /foo HTTP/1.1\r
                Host: localhost\r
                """);
            if (maxFormFields != -1)
                request.append(FormFields.MAX_FIELDS_ATTRIBUTE).append(": ").append(maxFormFields).append("\r\n");
            if (maxFormLength != -1)
                request.append(FormFields.MAX_LENGTH_ATTRIBUTE).append(": ").append(maxFormLength).append("\r\n");
            request.append("""
                Content-Type: application/x-www-form-urlencoded\r
                Content-Length: 27\r
                \r
                param1=value1&param2=value2\
                """);
            OutputStream output = socket.getOutputStream();
            output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            return HttpTester.parseResponse(input);
        }
    }
}
