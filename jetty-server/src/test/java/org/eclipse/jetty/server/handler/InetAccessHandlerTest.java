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

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class InetAccessHandlerTest
{
    private static Server _server;
    private static ServerConnector _connector1;
    private static ServerConnector _connector2;
    private static InetAccessHandler _handler;

    @BeforeAll
    public static void setUp() throws Exception
    {
        _server = new Server();
        _connector1 = new ServerConnector(_server);
        _connector1.setName("http_connector1");
        _connector2 = new ServerConnector(_server);
        _connector2.setName("http_connector2");
        _server.setConnectors(new Connector[]
            {_connector1, _connector2});

        _handler = new InetAccessHandler();
        _handler.setHandler(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
                throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
            }
        });
        _server.setHandler(_handler);
        _server.start();
    }

    @AfterAll
    public static void tearDown() throws Exception
    {
        _server.stop();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testHandler(String include, String exclude, String includeConnectors, String excludeConnectors, String code)
        throws Exception
    {
        _handler.clear();
        for (String inc : include.split(";", -1))
        {
            if (inc.length() > 0)
            {
                _handler.include(inc);
            }
        }
        for (String exc : exclude.split(";", -1))
        {
            if (exc.length() > 0)
            {
                _handler.exclude(exc);
            }
        }
        for (String inc : includeConnectors.split(";", -1))
        {
            if (inc.length() > 0)
            {
                _handler.includeConnector(inc);
            }
        }
        for (String exc : excludeConnectors.split(";", -1))
        {
            if (exc.length() > 0)
            {
                _handler.excludeConnector(exc);
            }
        }

        List<String> codePerConnector = new ArrayList<>();
        for (String nextCode : code.split(";", -1))
        {
            if (nextCode.length() > 0)
            {
                codePerConnector.add(nextCode);
            }
        }

        testConnector(_connector1.getLocalPort(), include, exclude, includeConnectors, excludeConnectors, codePerConnector.get(0));
        testConnector(_connector2.getLocalPort(), include, exclude, includeConnectors, excludeConnectors, codePerConnector.get(1));
    }

    private void testConnector(int port, String include, String exclude, String includeConnectors, String excludeConnectors, String code) throws IOException
    {
        try (Socket socket = new Socket("127.0.0.1", port);)
        {
            socket.setSoTimeout(5000);

            HttpTester.Request request = HttpTester.newRequest();
            request.setMethod("GET");
            request.setURI("/path");
            request.setHeader("Host", "127.0.0.1");
            request.setVersion(HttpVersion.HTTP_1_0);

            ByteBuffer output = request.generate();
            socket.getOutputStream().write(output.array(), output.arrayOffset() + output.position(), output.remaining());
            HttpTester.Input input = HttpTester.from(socket.getInputStream());
            HttpTester.Response response = HttpTester.parseResponse(input);
            Object[] params = new Object[]
                {
                    "Request WBHUC", include, exclude, includeConnectors, excludeConnectors, code, "Response",
                    response.getStatus()
                };
            assertEquals(Integer.parseInt(code), response.getStatus(), Arrays.deepToString(params));
        }
    }

    /**
     * Data for this test.
     *
     * @return Format of data: include;exclude;includeConnectors;excludeConnectors;assertionStatusCodePerConnector
     */
    public static Stream<Arguments> data()
    {
        Object[][] data = new Object[][]
            {
                // Empty lists 1
                {"", "", "", "", "200;200"},

                // test simple filters
                {"127.0.0.1", "", "", "", "200;200"},
                {"127.0.0.1-127.0.0.254", "", "", "", "200;200"},
                {"192.0.0.1", "", "", "", "403;403"},
                {"192.0.0.1-192.0.0.254", "", "", "", "403;403"},

                // test includeConnector
                {"127.0.0.1", "", "http_connector1", "", "200;200"},
                {"127.0.0.1-127.0.0.254", "", "http_connector1", "", "200;200"},
                {"192.0.0.1", "", "http_connector1", "", "403;200"},
                {"192.0.0.1-192.0.0.254", "", "http_connector1", "", "403;200"},
                {"192.0.0.1", "", "http_connector2", "", "200;403"},
                {"192.0.0.1-192.0.0.254", "", "http_connector2", "", "200;403"},

                // test includeConnector names where none of them match
                {"127.0.0.1", "", "nothttp", "", "200;200"},
                {"127.0.0.1-127.0.0.254", "", "nothttp", "", "200;200"},
                {"192.0.0.1", "", "nothttp", "", "200;200"},
                {"192.0.0.1-192.0.0.254", "", "nothttp", "", "200;200"},

                // text excludeConnector
                {"127.0.0.1", "", "", "http_connector1", "200;200"},
                {"127.0.0.1-127.0.0.254", "", "", "http_connector1", "200;200"},
                {"192.0.0.1", "", "", "http_connector1", "200;403"},
                {"192.0.0.1-192.0.0.254", "", "", "http_connector1", "200;403"},
                {"192.0.0.1", "", "", "http_connector2", "403;200"},
                {"192.0.0.1-192.0.0.254", "", "", "http_connector2", "403;200"},

                // test excludeConnector where none of them match.
                {"127.0.0.1", "", "", "nothttp", "200;200"},
                {"127.0.0.1-127.0.0.254", "", "", "nothttp", "200;200"},
                {"192.0.0.1", "", "", "nothttp", "403;403"},
                {"192.0.0.1-192.0.0.254", "", "", "nothttp", "403;403"},

                // both connectors are excluded
                {"127.0.0.1", "", "", "http_connector1;http_connector2", "200;200"},
                {"127.0.0.1-127.0.0.254", "", "", "http_connector1;http_connector2", "200;200"},
                {"192.0.0.1", "", "", "http_connector1;http_connector2", "200;200"},
                {"192.0.0.1-192.0.0.254", "", "", "http_connector1;http_connector2", "200;200"},

                // both connectors are included
                {"127.0.0.1", "", "http_connector1;http_connector2", "", "200;200"},
                {"127.0.0.1-127.0.0.254", "", "http_connector1;http_connector2", "", "200;200"},
                {"192.0.0.1", "", "http_connector1;http_connector2", "", "403;403"},
                {"192.0.0.1-192.0.0.254", "", "http_connector1;http_connector2", "", "403;403"},

                // exclude takes precedence over include
                {"127.0.0.1", "", "http_connector1;http_connector2", "http_connector1;http_connector2", "200;200"},
                {"127.0.0.1-127.0.0.254", "", "http_connector1;http_connector2", "http_connector1;http_connector2", "200;200"},
                {"192.0.0.1", "", "http_connector1;http_connector2", "http_connector1;http_connector2", "200;200"},
                {"192.0.0.1-192.0.0.254", "", "http_connector1;http_connector2", "http_connector1;http_connector2", "200;200"}
            };
        return Arrays.asList(data).stream().map(Arguments::of);
    }
}
