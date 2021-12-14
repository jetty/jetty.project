//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class LocalNameOverrideTest
{
    private static class DumpHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setCharacterEncoding("utf-8");
            response.setContentType("text/plain");
            PrintWriter out = response.getWriter();
            out.printf("ServerName=[%s]%n", request.getServerName());
            out.printf("ServerPort=[%d]%n", request.getServerPort());
            out.printf("LocalName=[%s]%n", request.getLocalName());
            out.printf("RequestURL=[%s]%n", request.getRequestURL());
        }
    }

    @Test
    public void testOverrideLocalNameHttp10NoHost() throws Exception
    {
        Server server = new Server();
        try
        {
            ServerConnector connector = new ServerConnector(server);
            connector.setLocalName("FooLocalName");
            connector.setPort(0);

            server.addConnector(connector);
            server.setHandler(new DumpHandler());
            server.start();

            try (Socket socket = new Socket("localhost", connector.getLocalPort());
                 OutputStream output = socket.getOutputStream();
                 InputStream input = socket.getInputStream())
            {
                String request =
                    "GET / HTTP/1.0\r\n" +
                        "\r\n";
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(input));

                assertNotNull(response, "response");

                assertThat("response.status", response.getStatus(), is(200));
                String responseContent = response.getContent();
                assertThat("response content", responseContent, allOf(
                    containsString("ServerName=[FooLocalName]"),
                    containsString("ServerPort=[" + connector.getLocalPort() + "]"),
                    containsString("LocalName=[FooLocalName]"),
                    containsString("RequestURL=[http://FooLocalName:" + connector.getLocalPort() + "/]")
                ));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    @Test
    @Disabled("Port override is not possible yet")
    public void testOverrideLocalNameHttp11NoHost() throws Exception
    {
        Server server = new Server();
        try
        {
            ServerConnector connector = new ServerConnector(server);
            connector.setLocalName("BarLocalName");
            connector.setPort(0);

            server.addConnector(connector);
            server.setHandler(new DumpHandler());
            server.start();

            try (Socket socket = new Socket("localhost", connector.getLocalPort());
                 OutputStream output = socket.getOutputStream();
                 InputStream input = socket.getInputStream())
            {
                String request =
                    "GET / HTTP/1.1\r\n" +
                        "Host: \r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(input));

                assertNotNull(response, "response");

                assertThat("response.status", response.getStatus(), is(200));
                String responseContent = response.getContent();
                assertThat("response content", responseContent, allOf(
                    containsString("ServerName=[BarLocalName]"),
                    // request uri is not absolute, so it's assumed to be the scheme from HttpConfiguration (default: "http")
                    // which has an assumed port if unspecified. (like in the above request)
                    containsString("ServerPort=[80]"),
                    // However the Host is empty, so the authority is unspecified for the request, so it uses the local name
                    containsString("LocalName=[BarLocalName]"),
                    // TODO: shouldn't this port also be overridable?
                    containsString("RequestURL=[http://BarLocalName:" + connector.getLocalPort() + "/]")
                ));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    @Test
    public void testOverrideLocalNameHttp11ValidHost() throws Exception
    {
        Server server = new Server();
        try
        {
            ServerConnector connector = new ServerConnector(server);
            connector.setLocalName("BarLocalName");
            connector.setPort(0);

            server.addConnector(connector);
            server.setHandler(new DumpHandler());
            server.start();

            try (Socket socket = new Socket("localhost", connector.getLocalPort());
                 OutputStream output = socket.getOutputStream();
                 InputStream input = socket.getInputStream())
            {
                String request =
                    "GET / HTTP/1.1\r\n" +
                        "Host: jetty.eclipse.org\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(input));

                assertNotNull(response, "response");

                assertThat("response.status", response.getStatus(), is(200));
                String responseContent = response.getContent();
                assertThat("response content", responseContent, allOf(
                    containsString("ServerName=[jetty.eclipse.org]"),
                    // request uri is not absolute, so it's assumed to be the scheme from HttpConfiguration (default: "http")
                    // which has an assumed port if unspecified. (like in the above request)
                    containsString("ServerPort=[80]"),
                    // Local name was overridden, so it remains the name seen by the handler
                    containsString("LocalName=[BarLocalName]"),
                    // ServerName is used for RequestURL
                    containsString("RequestURL=[http://jetty.eclipse.org/]")
                ));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }
}
