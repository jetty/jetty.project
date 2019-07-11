//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.tools.HttpTester;
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
    private static ServerConnector _connector;
    private static InetAccessHandler _handler;

    @BeforeAll
    public static void setUp() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _connector.setName("http");
        _server.setConnectors(new Connector[]
            {_connector});

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

        try (Socket socket = new Socket("127.0.0.1", _connector.getLocalPort());)
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

    public static Stream<Arguments> data()
    {
        Object[][] data = new Object[][]
            {
                // Empty lists
                {"", "", "", "", "200"},

                // test simple filters
                {"127.0.0.1", "", "", "", "200"},
                {"127.0.0.1-127.0.0.254", "", "", "", "200"},
                {"192.0.0.1", "", "", "", "403"},
                {"192.0.0.1-192.0.0.254", "", "", "", "403"},

                // test connector name filters
                {"127.0.0.1", "", "http", "", "200"},
                {"127.0.0.1-127.0.0.254", "", "http", "", "200"},
                {"192.0.0.1", "", "http", "", "403"},
                {"192.0.0.1-192.0.0.254", "", "http", "", "403"},

                {"127.0.0.1", "", "nothttp", "", "403"},
                {"127.0.0.1-127.0.0.254", "", "nothttp", "", "403"},
                {"192.0.0.1", "", "nothttp", "", "403"},
                {"192.0.0.1-192.0.0.254", "", "nothttp", "", "403"},

                {"127.0.0.1", "", "", "http", "403"},
                {"127.0.0.1-127.0.0.254", "", "", "http", "403"},
                {"192.0.0.1", "", "", "http", "403"},
                {"192.0.0.1-192.0.0.254", "", "", "http", "403"},

                {"127.0.0.1", "", "", "nothttp", "200"},
                {"127.0.0.1-127.0.0.254", "", "", "nothttp", "200"},
                {"192.0.0.1", "", "", "nothttp", "403"},
                {"192.0.0.1-192.0.0.254", "", "", "nothttp", "403"},
                };
        return Arrays.asList(data).stream().map(Arguments::of);
    }
}
