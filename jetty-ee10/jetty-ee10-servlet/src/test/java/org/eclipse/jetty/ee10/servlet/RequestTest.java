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

package org.eclipse.jetty.ee10.servlet;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class RequestTest
{
    private Server _server;
    private LocalConnector _connector;

    private void startServer(HttpServlet servlet) throws Exception
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        http.getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());

        _connector = new LocalConnector(_server, http);
        _server.addConnector(_connector);

        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.addServlet(servlet, "/*");

        _server.setHandler(servletContextHandler);
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        LifeCycle.stop(_server);
    }

    @Test
    public void testConnectRequestURLSameAsHost() throws Exception
    {
        final AtomicReference<String> resultRequestURL = new AtomicReference<>();
        final AtomicReference<String> resultRequestURI = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultRequestURL.set(request.getRequestURL().toString());
                resultRequestURI.set(request.getRequestURI());
            }
        });

        String rawResponse = _connector.getResponse(
            """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: myhost:9999\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.getRequestURL", resultRequestURL.get(), is("http://myhost:9999/"));
        assertThat("request.getRequestURI", resultRequestURI.get(), is("/"));
    }

    @Test
    public void testConnectRequestURLDifferentThanHost() throws Exception
    {
        final AtomicReference<String> resultRequestURL = new AtomicReference<>();
        final AtomicReference<String> resultRequestURI = new AtomicReference<>();

        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse resp)
            {
                resultRequestURL.set(request.getRequestURL().toString());
                resultRequestURI.set(request.getRequestURI());
            }
        });

        // per spec, "Host" is ignored if request-target is authority-form
        String rawResponse = _connector.getResponse(
            """
                CONNECT myhost:9999 HTTP/1.1\r
                Host: otherhost:8888\r
                Connection: close\r
                \r
                """);
        HttpTester.Response response = HttpTester.parseResponse(rawResponse);
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat("request.getRequestURL", resultRequestURL.get(), is("http://myhost:9999/"));
        assertThat("request.getRequestURI", resultRequestURI.get(), is("/"));
    }
}
