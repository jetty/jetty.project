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
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.EagerContentHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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

        CompletableFuture<Throwable> handlerErrorFuture = new CompletableFuture<>();
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new EagerContentHandler.FormContentLoaderFactory());
        _server.setHandler(eagerContentHandler);
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        eagerContentHandler.setHandler(servletContextHandler.getCoreContextHandler());
        servletContextHandler.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp)
            {
                try
                {
                    req.getParameterMap();
                }
                catch (Throwable t)
                {
                    handlerErrorFuture.complete(t);
                    throw t;
                }
            }
        }), "/");
        _server.start();

        // The response code should be 400 BAD_REQUEST.
        HttpTester.Response response = sendForm();
        assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
        assertThat(response.getContent(), containsString("Unable to parse form content"));

        // Expect an error from calling getParameterMap().
        Throwable throwable = handlerErrorFuture.get(5, TimeUnit.SECONDS);
        assertThat(throwable, instanceOf(BadMessageException.class));
    }

    @Test
    public void testEagerFormFieldsArePassedToTheServlet() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new EagerContentHandler.FormContentLoaderFactory());
        eagerContentHandler.setHandler(newServletContext().getCoreContextHandler());
        _server.setHandler(eagerContentHandler);
        _server.start();

        HttpTester.Response response = sendForm();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("param1"));
        assertThat(response.getContent(), containsString("param2"));
    }

    @Test
    public void testRequestAttributeAboveEagerContentHandlerIsApplied() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new EagerContentHandler.FormContentLoaderFactory());
        eagerContentHandler.setHandler(newServletContext().getCoreContextHandler());
        _server.setHandler(new Handler.Wrapper(eagerContentHandler)
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                request.setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, 1);
                return super.handle(request, response, callback);
            }
        });
        _server.start();

        assertThat(sendForm().getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testExtractFormParametersUsesEagerFormFields() throws Exception
    {
        EagerContentHandler eagerContentHandler = new EagerContentHandler(new EagerContentHandler.FormContentLoaderFactory());
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        eagerContentHandler.setHandler(servletContextHandler.getCoreContextHandler());
        servletContextHandler.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                // Called directly by the form and OpenID authenticators, bypassing getParameterMap().
                Fields fields = new Fields(true);
                org.eclipse.jetty.ee9.nested.Request.getBaseRequest(req).extractFormParameters(fields);
                resp.getWriter().print(fields.getNames());
            }
        }), "/");
        _server.setHandler(eagerContentHandler);
        _server.start();

        HttpTester.Response response = sendForm();
        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("param1"));
        assertThat(response.getContent(), containsString("param2"));
    }

    private ServletContextHandler newServletContext()
    {
        ServletContextHandler servletContextHandler = new ServletContextHandler();
        servletContextHandler.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                resp.getWriter().print(req.getParameterMap().keySet());
            }
        }), "/");
        return servletContextHandler;
    }

    private HttpTester.Response sendForm() throws Exception
    {
        try (Socket socket = new Socket("localhost", _connector.getLocalPort()))
        {
            String request = """
                POST /foo HTTP/1.1\r
                Host: localhost\r
                Content-Type: application/x-www-form-urlencoded\r
                Content-Length: 27\r
                Connection: close\r
                \r
                param1=value1&param2=value2\
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            return HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
        }
    }
}
