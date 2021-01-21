//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler;

import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.StringUtil;
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
    public void testHandler(String path, String include, String exclude, String includeConnectors, String excludeConnectors, String code)
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
                _handler.include(inc + "@");
            }
        }
        for (String exc : excludeConnectors.split(";", -1))
        {
            if (exc.length() > 0)
            {
                _handler.exclude(exc + "@");
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

        testConnector(_connector1.getLocalPort(), path, include, exclude, includeConnectors, excludeConnectors, codePerConnector.get(0));
        testConnector(_connector2.getLocalPort(), path, include, exclude, includeConnectors, excludeConnectors, codePerConnector.get(1));
    }

    private void testConnector(int port, String path, String include, String exclude, String includeConnectors, String excludeConnectors, String code) throws IOException
    {
        try (Socket socket = new Socket("127.0.0.1", port);)
        {
            socket.setSoTimeout(5000);

            HttpTester.Request request = HttpTester.newRequest();
            request.setMethod("GET");
            request.setURI(StringUtil.isEmpty(path) ? "/" : path);
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
                {"", "", "", "", "", "200;200"},

                // test simple filters
                {"", "127.0.0.1", "", "", "", "200;200"},
                {"", "127.0.0.1-127.0.0.254", "", "", "", "200;200"},
                {"", "127.0.0.1-127.0.0.254", "", "", "", "200;200"},
                {"", "192.0.0.1", "", "", "", "403;403"},
                {"", "192.0.0.1-192.0.0.254", "", "", "", "403;403"},

                // test includeConnector
                {"", "127.0.0.1", "", "http_connector1", "", "200;200"},
                {"", "127.0.0.1-127.0.0.254", "", "http_connector1", "", "200;200"},
                {"", "192.0.0.1", "", "http_connector1", "", "200;403"},
                {"", "192.0.0.1-192.0.0.254", "", "http_connector1", "", "200;403"},
                {"", "192.0.0.1", "", "http_connector2", "", "403;200"},
                {"", "192.0.0.1-192.0.0.254", "", "http_connector2", "", "403;200"},

                // test includeConnector names where none of them match
                {"", "127.0.0.1", "", "nothttp", "", "200;200"},
                {"", "127.0.0.1-127.0.0.254", "", "nothttp", "", "200;200"},
                {"", "192.0.0.1", "", "nothttp", "", "403;403"},
                {"", "192.0.0.1-192.0.0.254", "", "nothttp", "", "403;403"},

                // text excludeConnector
                {"", "127.0.0.1", "", "", "http_connector1", "403;200"},
                {"", "127.0.0.1-127.0.0.254", "", "", "http_connector1", "403;200"},
                {"", "192.0.0.1", "", "", "http_connector1", "403;403"},
                {"", "192.0.0.1-192.0.0.254", "", "", "http_connector1", "403;403"},
                {"", "192.0.0.1", "", "", "http_connector2", "403;403"},
                {"", "192.0.0.1-192.0.0.254", "", "", "http_connector2", "403;403"},

                // test excludeConnector where none of them match.
                {"", "127.0.0.1", "", "", "nothttp", "200;200"},
                {"", "127.0.0.1-127.0.0.254", "", "", "nothttp", "200;200"},
                {"", "192.0.0.1", "", "", "nothttp", "403;403"},
                {"", "192.0.0.1-192.0.0.254", "", "", "nothttp", "403;403"},

                // both connectors are excluded
                {"", "127.0.0.1", "", "", "http_connector1;http_connector2", "403;403"},
                {"", "127.0.0.1-127.0.0.254", "", "", "http_connector1;http_connector2", "403;403"},
                {"", "192.0.0.1", "", "", "http_connector1;http_connector2", "403;403"},
                {"", "192.0.0.1-192.0.0.254", "", "", "http_connector1;http_connector2", "403;403"},

                // both connectors are included
                {"", "127.0.0.1", "", "http_connector1;http_connector2", "", "200;200"},
                {"", "127.0.0.1-127.0.0.254", "", "http_connector1;http_connector2", "", "200;200"},
                {"", "192.0.0.1", "", "http_connector1;http_connector2", "", "200;200"},
                {"", "192.0.0.1-192.0.0.254", "", "http_connector1;http_connector2", "", "200;200"},
                {"", "", "127.0.0.1", "http_connector1;http_connector2", "", "403;403"},

                // exclude takes precedence over include
                {"", "127.0.0.1", "", "http_connector1;http_connector2", "http_connector1;http_connector2", "403;403"},
                {
                    "", "127.0.0.1-127.0.0.254", "", "http_connector1;http_connector2", "http_connector1;http_connector2",
                    "403;403"
                },
                {"", "192.0.0.1", "", "http_connector1;http_connector2", "http_connector1;http_connector2", "403;403"},
                {
                    "", "192.0.0.1-192.0.0.254", "", "http_connector1;http_connector2", "http_connector1;http_connector2",
                    "403;403"
                },

                // Test path specific include/exclude.
                {"/testPath", "", "", "http_connector1", "", "200;403"},
                {"/", "127.0.0.1", "127.0.0.1|/testPath", "http_connector1", "", "200;200"},
                {"/testPath", "127.0.0.1", "127.0.0.1|/testPath", "http_connector1", "", "403;403"},
                {"/notTestPath", "127.0.1.11|/testPath", "", "http_connector1", "", "200;403"},
                {"/testPath", "127.0.1.11|/testPath", "", "http_connector1", "", "200;403"},
                {"/testPath", "127.0.0.13|/testPath;127.0.0.1|/testPath", "", "http_connector1", "", "200;200"},
                {"/testPath", "127.0.0.1", "127.0.0.1|/testPath", "http_connector1", "", "403;403"},
                {"/", "127.0.0.1", "127.0.0.1|/testPath", "http_connector1", "", "200;200"},
                {"/a/b", "", "127.0.0.1|/a/*", "", "", "403;403"},
                {"/b/a", "", "127.0.0.1|/a/*", "", "", "200;200"},
                {"/org/eclipse/jetty/test.html", "127.0.0.1|*.html", "127.0.0.1|*.xml", "", "", "200;200"},
                {"/org/eclipse/jetty/test.xml", "127.0.0.1|*.html", "127.0.0.1|*.xml", "", "", "403;403"},
                {"/org/eclipse/jetty/test.pdf", "127.0.0.1|*.html", "127.0.0.1|*.xml", "", "", "403;403"},
                {"/a/test.html", "", "127.0.0.1|*.html;127.0.0.10|/a/*", "", "", "403;403"},
                {"/foo/bar/test.xml", "127.0.0.1|/foo/*", "127.0.0.0-127.0.0.2|*.html", "", "", "200;200"},
                {"/foo/bar/test.html", "127.0.0.1|/foo/*", "127.0.0.0-127.0.0.2|*.html", "", "", "403;403"},
                {"/foo/bar/test.xml", "127.0.0.1|/foo/bar/*", "127.0.0.1|/foo/*", "", "", "403;403"}
            };
        return Arrays.stream(data).map(Arguments::of);
    }
}
