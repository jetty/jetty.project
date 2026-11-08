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
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.FormFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
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
        _server.setHandler(formLimitsHandler(servletContextHandler, maxFormFields, maxFormLength));
        _server.start();

        HttpTester.Response response = sendForm();
        assertThat(response.getStatus(), is(expectedStatusCode));
    }

    @Test
    public void testRequestAttributeTightensContextKeysLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.setMaxFormKeys(10);
        _server.setHandler(formLimitsHandler(servletContextHandler, FormFields.MAX_FIELDS_ATTRIBUTE, 1));
        _server.start();

        assertThat(sendForm().getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testRequestAttributeRelaxesContextKeysLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.setMaxFormKeys(1);
        _server.setHandler(formLimitsHandler(servletContextHandler, FormFields.MAX_FIELDS_ATTRIBUTE, 10));
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
        _server.setHandler(formLimitsHandler(servletContextHandler, FormFields.MAX_LENGTH_ATTRIBUTE, 26));
        _server.start();

        assertThat(sendForm().getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testRequestAttributeRelaxesContextLengthLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.setMaxFormContentSize(26);
        _server.setHandler(formLimitsHandler(servletContextHandler, FormFields.MAX_LENGTH_ATTRIBUTE, 27));
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
        servletContextHandler.getCoreContextHandler().getContext().setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, 1);
        _server.setHandler(servletContextHandler.getCoreContextHandler());
        _server.start();

        assertThat(sendForm().getStatus(), is(HttpStatus.BAD_REQUEST_400));
    }

    @Test
    public void testRequestAttributeOverridesContextAttributeLimit() throws Exception
    {
        ServletContextHandler servletContextHandler = newServletContext();
        servletContextHandler.getCoreContextHandler().getContext().setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, 1);
        _server.setHandler(formLimitsHandler(servletContextHandler, FormFields.MAX_FIELDS_ATTRIBUTE, 10));
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

    private Handler formLimitsHandler(ServletContextHandler servletContextHandler, String attribute, int value)
    {
        return new Handler.Wrapper(servletContextHandler.getCoreContextHandler())
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                request.setAttribute(attribute, value);
                return super.handle(request, response, callback);
            }
        };
    }

    /**
     * Sets both limits, leaving a limit of -1 unset so that the context default applies.
     * The values are set as Strings, as they would be if they came from configuration,
     * so that the String form of the attribute values is covered as well as the int form.
     */
    private Handler formLimitsHandler(ServletContextHandler servletContextHandler, int maxFormFields, int maxFormLength)
    {
        return new Handler.Wrapper(servletContextHandler.getCoreContextHandler())
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                if (maxFormFields != -1)
                    request.setAttribute(FormFields.MAX_FIELDS_ATTRIBUTE, Integer.toString(maxFormFields));
                if (maxFormLength != -1)
                    request.setAttribute(FormFields.MAX_LENGTH_ATTRIBUTE, Integer.toString(maxFormLength));
                return super.handle(request, response, callback);
            }
        };
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
                \r
                param1=value1&param2=value2\
                """;
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            return HttpTester.parseResponse(input);
        }
    }
}
