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
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

public class RejectMissingAuthorityCustomizerTest
{
    public static Stream<Arguments> badHostValues()
    {
        List<Arguments> hostValues = new ArrayList<>();

        // No hosts
        hostValues.add(Arguments.of(""));
        hostValues.add(Arguments.of(":"));
        hostValues.add(Arguments.of(":1111"));
        hostValues.add(Arguments.of("\":\""));
        hostValues.add(Arguments.of("\":1111\""));

        // Bad Ports
        hostValues.add(Arguments.of("jetty.eclipse.org:0"));
        hostValues.add(Arguments.of("jetty.eclipse.org:-1"));
        hostValues.add(Arguments.of("jetty.eclipse.org:-88"));
        hostValues.add(Arguments.of("jetty.eclipse.org:880088"));
        hostValues.add(Arguments.of("jetty.eclipse.org:923479823487953249083252765243"));

        return hostValues.stream();
    }

    @ParameterizedTest
    @MethodSource("badHostValues")
    public void testHttp11MissingAuthorityBadHostHeader(String hostHeaderValue) throws Exception
    {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new RejectMissingAuthorityCustomizer());
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                fail("Should not have reached this handler: serverName=" + request.getServerName());
            }
        });
        server.start();
        try
        {
            try (Socket socket = new Socket("localhost", connector.getLocalPort()))
            {
                try (OutputStream output = socket.getOutputStream())
                {
                    String request =
                        "GET /foo HTTP/1.1\r\n" +
                            "Host: " + hostHeaderValue + "\r\n" +
                            "Connection: close\r\n" +
                            "\r\n";
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    HttpTester.Input input = HttpTester.from(socket.getInputStream());
                    HttpTester.Response response = HttpTester.parseResponse(input);
                    assertNotNull(response);

                    assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
                }
            }
        }
        finally
        {
            server.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("badHostValues")
    public void testHttp10MissingAuthorityBadHostHeader(String hostHeaderValue) throws Exception
    {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new RejectMissingAuthorityCustomizer());
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                fail("Should not have reached this handler: serverName=" + request.getServerName());
            }
        });
        server.start();
        try
        {
            try (Socket socket = new Socket("localhost", connector.getLocalPort()))
            {
                try (OutputStream output = socket.getOutputStream())
                {
                    String request =
                        "GET /foo HTTP/1.0\r\n" +
                            "Host: " + hostHeaderValue + "\r\n" +
                            "\r\n";
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    HttpTester.Input input = HttpTester.from(socket.getInputStream());
                    HttpTester.Response response = HttpTester.parseResponse(input);
                    assertNotNull(response);

                    assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
                }
            }
        }
        finally
        {
            server.stop();
        }
    }

    public static Stream<Arguments> goodAbsoluteUris()
    {
        List<Arguments> absUris = new ArrayList<>();

        absUris.add(Arguments.of("http://jetty.eclipse.org:8888/", "jetty.eclipse.org", 8888));
        absUris.add(Arguments.of("https://jetty.eclipse.org:8443/", "jetty.eclipse.org", 8443));
        absUris.add(Arguments.of("https://jetty.eclipse.org/", "jetty.eclipse.org", 443));
        absUris.add(Arguments.of("https://jetty.eclipse.org:8888/", "jetty.eclipse.org", 8888));
        absUris.add(Arguments.of("http://-/1234", "-", 80));
        absUris.add(Arguments.of("http://*/1234", "*", 80));

        return absUris.stream();
    }

    @ParameterizedTest
    @MethodSource("goodAbsoluteUris")
    public void testHttp10ValidAuthorityAbsoluteRequestUriNoHostHeader(String absUri, String expectedServerName, int expectedServerPort) throws Exception
    {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new RejectMissingAuthorityCustomizer());
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
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
                out.printf("RequestURL=[%s]%n", request.getRequestURL());
            }
        });
        server.start();
        try
        {
            try (Socket socket = new Socket("localhost", connector.getLocalPort()))
            {
                try (OutputStream output = socket.getOutputStream())
                {
                    String request =
                        "GET " + absUri + " HTTP/1.0\r\n" +
                            "\r\n";
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    HttpTester.Input input = HttpTester.from(socket.getInputStream());
                    HttpTester.Response response = HttpTester.parseResponse(input);
                    assertNotNull(response);

                    assertThat(response.getStatus(), is(HttpStatus.OK_200));
                    String responseBody = response.getContent();
                    assertThat(responseBody, allOf(
                        containsString("ServerName=[" + expectedServerName + "]"),
                        containsString("ServerPort=[" + expectedServerPort + "]"),
                        containsString("RequestURL=[" + absUri + "]")
                    ));
                }
            }
        }
        finally
        {
            server.stop();
        }
    }

    public static Stream<Arguments> badAbsoluteUris()
    {
        List<Arguments> absUris = new ArrayList<>();

        // schemeless (looks like path atm)
        absUris.add(Arguments.of("//jetty.eclipse.org:8888/"));
        // bad ports (HostPort failures)
        absUris.add(Arguments.of("http://-:-/1234"));
        absUris.add(Arguments.of("http://-:-80/1234"));
        absUris.add(Arguments.of("http://-:ffff/1234"));
        // no authority (valid, reaches RejectMissingAuthorityCustomizer)
        absUris.add(Arguments.of("file:///1234"));
        absUris.add(Arguments.of("http:///path"));
        absUris.add(Arguments.of("mobile:///abcd"));

        return absUris.stream();
    }

    @ParameterizedTest
    @MethodSource("badAbsoluteUris")
    public void testHttp10MissingAuthorityAbsoluteRequestUriNoHostHeader(String absUri) throws Exception
    {
        Server server = new Server();
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new RejectMissingAuthorityCustomizer());
        ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);
        server.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                fail("Should not have reached this handler: serverName=" + request.getServerName());
            }
        });
        server.start();
        try
        {
            try (Socket socket = new Socket("localhost", connector.getLocalPort()))
            {
                try (OutputStream output = socket.getOutputStream())
                {
                    String request =
                        "GET " + absUri + " HTTP/1.0\r\n" +
                            "\r\n";
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    HttpTester.Input input = HttpTester.from(socket.getInputStream());
                    HttpTester.Response response = HttpTester.parseResponse(input);
                    assertNotNull(response);

                    assertThat(response.getStatus(), is(HttpStatus.BAD_REQUEST_400));
                }
            }
        }
        finally
        {
            server.stop();
        }
    }
}
