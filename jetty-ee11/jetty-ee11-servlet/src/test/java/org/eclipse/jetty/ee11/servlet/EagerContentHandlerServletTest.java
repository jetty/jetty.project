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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
 import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.EagerContentHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class EagerContentHandlerServletTest
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

    @Test
    public void testEagerFormFieldsLimits() throws Exception
    {
        // Set the server context limit for maxFormContentSize.
        _server.getContext().setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", "15");

        CountDownLatch processing = new CountDownLatch(2);
        CompletableFuture<Throwable> handlerErrorFuture = new CompletableFuture<>();
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new EagerContentHandler.FormContentLoaderFactory());
        _server.setHandler(eagerContentHandler);
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        eagerContentHandler.setHandler(servletContextHandler);
        servletContextHandler.addServlet(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                try
                {
                    processing.countDown();
                    req.getParameterMap();
                }
                catch (Throwable t)
                {
                    handlerErrorFuture.complete(t);
                    throw t;
                }
            }
        }, "/");
        _server.start();

        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                POST /foo HTTP/1.1\r
                Host: localhost\r
                Content-Type: application/x-www-form-urlencoded\r
                Content-Length: 27\r
                \r
                param1=value1&param2=value2\
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect an error from calling getParameterMap().
            Throwable throwable = handlerErrorFuture.get(5, TimeUnit.SECONDS);
            assertThat(throwable, instanceOf(HttpException.IllegalStateException.class));

            // The response code should be 400 BAD_REQUEST.
            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
            assertThat(response.getContent(), containsString("Unable to parse form content"));
        }
    }

    public static Stream<Arguments> formLimitsProvider()
    {
        // The form content has a size of 27 bytes with 2 fields.
        return Stream.of(
            Arguments.of(-1, -1, HttpStatus.OK_200),
            Arguments.of(100, 100, HttpStatus.OK_200),
            Arguments.of(2, -1, HttpStatus.OK_200),
            Arguments.of(1, -1, HttpStatus.BAD_REQUEST_400),
            Arguments.of(-1, 27, HttpStatus.OK_200),
            Arguments.of(-1, 26, HttpStatus.BAD_REQUEST_400)
            );
    }

    @ParameterizedTest
    @MethodSource("formLimitsProvider")
    public void perRequestFormLimitsTest(int maxFormFields, int maxFormLength, int expectedStatusCode) throws Exception
    {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
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
        servletContextHandler.addServlet(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                req.getParameterMap();
            }
        }, "/");

        _server.start();

        HttpTester.Response response = getResponse(maxFormFields, maxFormLength);
        assertThat(response.getStatus(), is(expectedStatusCode));
    }

    private HttpTester.Response getResponse(int maxFormFields, int maxFormLength) throws Exception
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
