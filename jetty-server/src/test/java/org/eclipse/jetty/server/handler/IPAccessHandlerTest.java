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

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IPAccessHandlerTest
{
    private Server _server;
    private NetworkConnector _connector;
    private IPAccessHandler _handler;

    @BeforeEach
    public void setUp()
        throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.setConnectors(new Connector[]{_connector});

        _handler = new IPAccessHandler();
        _handler.setHandler(new ScopedHandler()
        {
            @Override
            public void doScope(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    baseRequest.setServletPath(baseRequest.getPathInfo());
                    baseRequest.setPathInfo(null);
                    super.doScope(target, baseRequest, request, response);
                }
                finally
                {
                    baseRequest.setPathInfo(baseRequest.getServletPath());
                    baseRequest.setServletPath(null);
                }
            }

            @Override
            public void doHandle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                response.setStatus(HttpStatus.OK_200);
            }
        });
    }

    @AfterEach
    public void tearDown()
        throws Exception
    {
        _server.stop();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testHandler(String white, String black, String host, String uri, String code, boolean byPath)
        throws Exception
    {
        _server.setHandler(_handler);
        _server.start();

        _handler.setWhite(white.split(";", -1));
        _handler.setBlack(black.split(";", -1));
        _handler.setWhiteListByPath(byPath);

        String request = "GET " + uri + " HTTP/1.1\n" + "Host: " + host + "\n\n";
        Socket socket = new Socket("127.0.0.1", _connector.getLocalPort());
        socket.setSoTimeout(5000);
        try (OutputStream output = socket.getOutputStream();)
        {
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            Response response = readResponse(input);
            Object[] params = new Object[]{
                "Request WBHUC", white, black, host, uri, code,
                "Response", response.getCode()
            };
            assertEquals(code, response.getCode(), Arrays.deepToString(params));
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testContext(String white, String black, String host, String uri, String code, boolean byPath)
        throws Exception
    {
        ContextHandler context = new ContextHandler(_server, "/ctx");
        context.setHandler(_handler);
        _server.setHandler(context);
        _server.start();

        white = white.replaceAll("\\|/", "|/ctx/");
        black = black.replaceAll("\\|/", "|/ctx/");

        Assumptions.assumeFalse(white.endsWith("|"));
        Assumptions.assumeFalse(black.endsWith("|"));
        _handler.setWhite(white.split(";", -1));
        _handler.setBlack(black.split(";", -1));
        _handler.setWhiteListByPath(byPath);

        String request = "GET /ctx" + uri + " HTTP/1.1\n" + "Host: " + host + "\n\n";
        Socket socket = new Socket("127.0.0.1", _connector.getLocalPort());
        socket.setSoTimeout(5000);
        try (OutputStream output = socket.getOutputStream();)
        {
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            Response response = readResponse(input);
            Object[] params = new Object[]{
                "Request WBHUC", white, black, host, uri, code,
                "Response", response.getCode()
            };
            assertEquals(code, response.getCode(), Arrays.deepToString(params));
        }
    }

    protected Response readResponse(BufferedReader reader)
        throws IOException
    {
        // Simplified parser for HTTP responses
        String line = reader.readLine();
        if (line == null)
            throw new EOFException();
        Matcher responseLine = Pattern.compile("HTTP/1\\.1\\s+(\\d+)").matcher(line);
        assertTrue(responseLine.lookingAt());
        String code = responseLine.group(1);

        Map<String, String> headers = new LinkedHashMap<>();
        while ((line = reader.readLine()) != null)
        {
            if (line.trim().length() == 0)
                break;

            Matcher header = Pattern.compile("([^:]+):\\s*(.*)").matcher(line);
            assertTrue(header.lookingAt());
            String headerName = header.group(1);
            String headerValue = header.group(2);
            headers.put(headerName.toLowerCase(Locale.ENGLISH), headerValue.toLowerCase(Locale.ENGLISH));
        }

        StringBuilder body = new StringBuilder();
        if (headers.containsKey("content-length"))
        {
            int length = Integer.parseInt(headers.get("content-length"));
            for (int i = 0; i < length; ++i)
            {
                char c = (char)reader.read();
                body.append(c);
            }
        }
        else if ("chunked".equals(headers.get("transfer-encoding")))
        {
            while ((line = reader.readLine()) != null)
            {
                if ("0".equals(line))
                {
                    line = reader.readLine();
                    assertEquals("", line);
                    break;
                }

                int length = Integer.parseInt(line, 16);
                for (int i = 0; i < length; ++i)
                {
                    char c = (char)reader.read();
                    body.append(c);
                }
                line = reader.readLine();
                assertEquals("", line);
            }
        }

        return new Response(code, headers, body.toString().trim());
    }

    protected class Response
    {
        private final String code;
        private final Map<String, String> headers;
        private final String body;

        private Response(String code, Map<String, String> headers, String body)
        {
            this.code = code;
            this.headers = headers;
            this.body = body;
        }

        public String getCode()
        {
            return code;
        }

        public Map<String, String> getHeaders()
        {
            return headers;
        }

        public String getBody()
        {
            return body;
        }

        @Override
        public String toString()
        {
            StringBuilder builder = new StringBuilder();
            builder.append(code).append("\r\n");
            for (Map.Entry<String, String> entry : headers.entrySet())
            {
                builder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            builder.append("\r\n");
            builder.append(body);
            return builder.toString();
        }
    }

    public static Stream<Arguments> data()
    {
        Object[][] data = new Object[][]{
            // Empty lists
            {"", "", "127.0.0.1", "/", "200", false},
            {"", "", "127.0.0.1", "/dump/info", "200", false},

            // White list
            {"127.0.0.1", "", "127.0.0.1", "/", "200", false},
            {"127.0.0.1", "", "127.0.0.1", "/dispatch", "200", false},
            {"127.0.0.1", "", "127.0.0.1", "/dump/info", "200", false},

            {"127.0.0.1|/", "", "127.0.0.1", "/", "200", false},
            {"127.0.0.1|/", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.1|/", "", "127.0.0.1", "/dump/info", "403", false},

            {"127.0.0.1|/*", "", "127.0.0.1", "/", "200", false},
            {"127.0.0.1|/*", "", "127.0.0.1", "/dispatch", "200", false},
            {"127.0.0.1|/*", "", "127.0.0.1", "/dump/info", "200", false},

            {"127.0.0.1|/dump/*", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.1|/dump/*", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.1|/dump/*", "", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.1|/dump/*", "", "127.0.0.1", "/dump/test", "200", false},

            {"127.0.0.1|/dump/info", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.1|/dump/info", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.1|/dump/info", "", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.1|/dump/info", "", "127.0.0.1", "/dump/test", "403", false},

            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/dump/test", "200", false},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/dump/fail", "403", false},

            {"127.0.0.0-2|", "", "127.0.0.1", "/", "200", false},
            {"127.0.0.0-2|", "", "127.0.0.1", "/dump/info", "403", false},

            {"127.0.0.0-2|/", "", "127.0.0.1", "/", "200", false},
            {"127.0.0.0-2|/", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.0-2|/", "", "127.0.0.1", "/dump/info", "403", false},

            {"127.0.0.0-2|/dump/*", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.0-2|/dump/*", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.0-2|/dump/*", "", "127.0.0.1", "/dump/info", "200", false},

            {"127.0.0.0-2|/dump/info", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.0-2|/dump/info", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.0-2|/dump/info", "", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.0-2|/dump/info", "", "127.0.0.1", "/dump/test", "403", false},

            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/dump/test", "200", false},
            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/dump/fail", "403", false},

            // Black list
            {"", "127.0.0.1", "127.0.0.1", "/", "403", false},
            {"", "127.0.0.1", "127.0.0.1", "/dispatch", "403", false},
            {"", "127.0.0.1", "127.0.0.1", "/dump/info", "403", false},

            {"", "127.0.0.1|/", "127.0.0.1", "/", "403", false},
            {"", "127.0.0.1|/", "127.0.0.1", "/dispatch", "200", false},
            {"", "127.0.0.1|/", "127.0.0.1", "/dump/info", "200", false},

            {"", "127.0.0.1|/*", "127.0.0.1", "/", "403", false},
            {"", "127.0.0.1|/*", "127.0.0.1", "/dispatch", "403", false},
            {"", "127.0.0.1|/*", "127.0.0.1", "/dump/info", "403", false},

            {"", "127.0.0.1|/dump/*", "127.0.0.1", "/", "200", false},
            {"", "127.0.0.1|/dump/*", "127.0.0.1", "/dispatch", "200", false},
            {"", "127.0.0.1|/dump/*", "127.0.0.1", "/dump/info", "403", false},
            {"", "127.0.0.1|/dump/*", "127.0.0.1", "/dump/test", "403", false},

            {"", "127.0.0.1|/dump/info", "127.0.0.1", "/", "200", false},
            {"", "127.0.0.1|/dump/info", "127.0.0.1", "/dispatch", "200", false},
            {"", "127.0.0.1|/dump/info", "127.0.0.1", "/dump/info", "403", false},
            {"", "127.0.0.1|/dump/info", "127.0.0.1", "/dump/test", "200", false},

            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/", "200", false},
            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/dispatch", "200", false},
            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/dump/info", "403", false},
            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/dump/test", "403", false},
            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/dump/fail", "200", false},

            {"", "127.0.0.0-2|", "127.0.0.1", "/", "403", false},
            {"", "127.0.0.0-2|", "127.0.0.1", "/dump/info", "200", false},

            {"", "127.0.0.0-2|/", "127.0.0.1", "/", "403", false},
            {"", "127.0.0.0-2|/", "127.0.0.1", "/dispatch", "200", false},
            {"", "127.0.0.0-2|/", "127.0.0.1", "/dump/info", "200", false},

            {"", "127.0.0.0-2|/dump/*", "127.0.0.1", "/", "200", false},
            {"", "127.0.0.0-2|/dump/*", "127.0.0.1", "/dispatch", "200", false},
            {"", "127.0.0.0-2|/dump/*", "127.0.0.1", "/dump/info", "403", false},

            {"", "127.0.0.0-2|/dump/info", "127.0.0.1", "/", "200", false},
            {"", "127.0.0.0-2|/dump/info", "127.0.0.1", "/dispatch", "200", false},
            {"", "127.0.0.0-2|/dump/info", "127.0.0.1", "/dump/info", "403", false},
            {"", "127.0.0.0-2|/dump/info", "127.0.0.1", "/dump/test", "200", false},

            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/", "200", false},
            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/dispatch", "200", false},
            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/dump/info", "403", false},
            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/dump/test", "403", false},
            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/dump/fail", "200", false},

            // Both lists
            {"127.0.0.1|/dump", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump", "200", false},
            {"127.0.0.1|/dump", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump/info", "403", false},
            {"127.0.0.1|/dump", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump/fail", "403", false},

            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump", "200", false},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump/fail", "403", false},

            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/test;127.0.0.1|/dump/fail", "127.0.0.1", "/dump", "200", false},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/test;127.0.0.1|/dump/fail", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/test;127.0.0.1|/dump/fail", "127.0.0.1", "/dump/test", "403", false},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/test;127.0.0.1|/dump/fail", "127.0.0.1", "/dump/fail", "403", false},

            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1|/dump/test", "127.0.0.1", "/dump", "403", false},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1|/dump/test", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1|/dump/test", "127.0.0.1", "/dump/test", "403", false},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1|/dump/test", "127.0.0.1", "/dump/fail", "403", false},

            {"127.0.0.1|/;127.0.0.0-2|/dump/*", "127.0.0.0,1|/dump/fail", "127.0.0.1", "/", "200", false},
            {"127.0.0.1|/;127.0.0.0-2|/dump/*", "127.0.0.0,1|/dump/fail", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.1|/;127.0.0.0-2|/dump/*", "127.0.0.0,1|/dump/fail", "127.0.0.1", "/dump/fail", "403", false},

            // Different address
            {"127.0.0.2", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.2", "", "127.0.0.1", "/dump/info", "403", false},

            {"127.0.0.2|/dump/*", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.2|/dump/*", "", "127.0.0.1", "/dump/info", "403", false},

            {"127.0.0.2|/dump/info", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.2|/dump/info", "", "127.0.0.1", "/dump/info", "403", false},
            {"127.0.0.2|/dump/info", "", "127.0.0.1", "/dump/test", "403", false},

            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/", "403", false},
            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/dispatch", "403", false},
            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/dump/info", "200", false},
            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/dump/test", "403", false},
            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/dump/fail", "403", false},

            {"172.0.0.0-255", "", "127.0.0.1", "/", "403", false},
            {"172.0.0.0-255", "", "127.0.0.1", "/dump/info", "403", false},

            {"172.0.0.0-255|/dump/*;127.0.0.0-255|/dump/*", "", "127.0.0.1", "/", "403", false},
            {"172.0.0.0-255|/dump/*;127.0.0.0-255|/dump/*", "", "127.0.0.1", "/dispatch", "403", false},
            {"172.0.0.0-255|/dump/*;127.0.0.0-255|/dump/*", "", "127.0.0.1", "/dump/info", "200", false},

            // Match by path starts with [117]
            // test cases affected by _whiteListByPath highlighted accordingly

            {"", "", "127.0.0.1", "/", "200", true},
            {"", "", "127.0.0.1", "/dump/info", "200", true},

            // White list
            {"127.0.0.1", "", "127.0.0.1", "/", "200", true},
            {"127.0.0.1", "", "127.0.0.1", "/dispatch", "200", true},
            {"127.0.0.1", "", "127.0.0.1", "/dump/info", "200", true},

            {"127.0.0.1|/", "", "127.0.0.1", "/", "200", true},
            {"127.0.0.1|/", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.1|/", "", "127.0.0.1", "/dump/info", "200", true}, // _whiteListByPath

            {"127.0.0.1|/*", "", "127.0.0.1", "/", "200", true},
            {"127.0.0.1|/*", "", "127.0.0.1", "/dispatch", "200", true},
            {"127.0.0.1|/*", "", "127.0.0.1", "/dump/info", "200", true},

            {"127.0.0.1|/dump/*", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump/*", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump/*", "", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.1|/dump/*", "", "127.0.0.1", "/dump/test", "200", true},

            {"127.0.0.1|/dump/info", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump/info", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump/info", "", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.1|/dump/info", "", "127.0.0.1", "/dump/test", "200", true}, // _whiteListByPath

            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/dump/test", "200", true},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "", "127.0.0.1", "/dump/fail", "200", true}, // _whiteListByPath

            {"127.0.0.0-2|", "", "127.0.0.1", "/", "200", true},
            {"127.0.0.0-2|", "", "127.0.0.1", "/dump/info", "200", true},

            {"127.0.0.0-2|/", "", "127.0.0.1", "/", "200", true},
            {"127.0.0.0-2|/", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.0-2|/", "", "127.0.0.1", "/dump/info", "200", true}, // _whiteListByPath

            {"127.0.0.0-2|/dump/*", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.0-2|/dump/*", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.0-2|/dump/*", "", "127.0.0.1", "/dump/info", "200", true},

            {"127.0.0.0-2|/dump/info", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.0-2|/dump/info", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.0-2|/dump/info", "", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.0-2|/dump/info", "", "127.0.0.1", "/dump/test", "200", true}, // _whiteListByPath

            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/dump/test", "200", true},
            {"127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "", "127.0.0.1", "/dump/fail", "200", true}, // _whiteListByPath

            // Black list
            {"", "127.0.0.1", "127.0.0.1", "/", "403", true},
            {"", "127.0.0.1", "127.0.0.1", "/dispatch", "403", true},
            {"", "127.0.0.1", "127.0.0.1", "/dump/info", "403", true},

            {"", "127.0.0.1|/", "127.0.0.1", "/", "403", true},
            {"", "127.0.0.1|/", "127.0.0.1", "/dispatch", "200", true},
            {"", "127.0.0.1|/", "127.0.0.1", "/dump/info", "200", true},

            {"", "127.0.0.1|/*", "127.0.0.1", "/", "403", true},
            {"", "127.0.0.1|/*", "127.0.0.1", "/dispatch", "403", true},
            {"", "127.0.0.1|/*", "127.0.0.1", "/dump/info", "403", true},

            {"", "127.0.0.1|/dump/*", "127.0.0.1", "/", "200", true},
            {"", "127.0.0.1|/dump/*", "127.0.0.1", "/dispatch", "200", true},
            {"", "127.0.0.1|/dump/*", "127.0.0.1", "/dump/info", "403", true},
            {"", "127.0.0.1|/dump/*", "127.0.0.1", "/dump/test", "403", true},

            {"", "127.0.0.1|/dump/info", "127.0.0.1", "/", "200", true},
            {"", "127.0.0.1|/dump/info", "127.0.0.1", "/dispatch", "200", true},
            {"", "127.0.0.1|/dump/info", "127.0.0.1", "/dump/info", "403", true},
            {"", "127.0.0.1|/dump/info", "127.0.0.1", "/dump/test", "200", true},

            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/", "200", true},
            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/dispatch", "200", true},
            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/dump/info", "403", true},
            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/dump/test", "403", true},
            {"", "127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1", "/dump/fail", "200", true},

            {"", "127.0.0.0-2|", "127.0.0.1", "/", "403", true},
            {"", "127.0.0.0-2|", "127.0.0.1", "/dump/info", "200", true},

            {"", "127.0.0.0-2|/", "127.0.0.1", "/", "403", true},
            {"", "127.0.0.0-2|/", "127.0.0.1", "/dispatch", "200", true},
            {"", "127.0.0.0-2|/", "127.0.0.1", "/dump/info", "200", true},

            {"", "127.0.0.0-2|/dump/*", "127.0.0.1", "/", "200", true},
            {"", "127.0.0.0-2|/dump/*", "127.0.0.1", "/dispatch", "200", true},
            {"", "127.0.0.0-2|/dump/*", "127.0.0.1", "/dump/info", "403", true},

            {"", "127.0.0.0-2|/dump/info", "127.0.0.1", "/", "200", true},
            {"", "127.0.0.0-2|/dump/info", "127.0.0.1", "/dispatch", "200", true},
            {"", "127.0.0.0-2|/dump/info", "127.0.0.1", "/dump/info", "403", true},
            {"", "127.0.0.0-2|/dump/info", "127.0.0.1", "/dump/test", "200", true},

            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/", "200", true},
            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/dispatch", "200", true},
            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/dump/info", "403", true},
            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/dump/test", "403", true},
            {"", "127.0.0.0-2|/dump/info;127.0.0.0-2|/dump/test", "127.0.0.1", "/dump/fail", "200", true},

            // Both lists
            {"127.0.0.1|/dump", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump", "200", true},
            {"127.0.0.1|/dump", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump/info", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump/fail", "403", true},

            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump", "200", true},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/fail", "127.0.0.1", "/dump/fail", "403", true},

            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/test;127.0.0.1|/dump/fail", "127.0.0.1", "/dump", "200", true},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/test;127.0.0.1|/dump/fail", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/test;127.0.0.1|/dump/fail", "127.0.0.1", "/dump/test", "403", true},
            {"127.0.0.1|/dump/*", "127.0.0.1|/dump/test;127.0.0.1|/dump/fail", "127.0.0.1", "/dump/fail", "403", true},

            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1|/dump/test", "127.0.0.1", "/dump", "200", true},
            // _whiteListByPath
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1|/dump/test", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1|/dump/test", "127.0.0.1", "/dump/test", "403", true},
            {"127.0.0.1|/dump/info;127.0.0.1|/dump/test", "127.0.0.1|/dump/test", "127.0.0.1", "/dump/fail", "200", true},
            // _whiteListByPath

            {"127.0.0.1|/;127.0.0.0-2|/dump/*", "127.0.0.0,1|/dump/fail", "127.0.0.1", "/", "200", true},
            {"127.0.0.1|/;127.0.0.0-2|/dump/*", "127.0.0.0,1|/dump/fail", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.1|/;127.0.0.0-2|/dump/*", "127.0.0.0,1|/dump/fail", "127.0.0.1", "/dump/fail", "403", true},

            // Different address
            {"127.0.0.2", "", "127.0.0.1", "/", "403", true},
            {"127.0.0.2", "", "127.0.0.1", "/dump/info", "403", true},

            {"127.0.0.2|/dump/*", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.2|/dump/*", "", "127.0.0.1", "/dump/info", "403", true},

            {"127.0.0.2|/dump/info", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.2|/dump/info", "", "127.0.0.1", "/dump/info", "403", true},
            {"127.0.0.2|/dump/info", "", "127.0.0.1", "/dump/test", "200", true}, // _whiteListByPath

            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/dump/info", "200", true},
            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/dump/test", "403", true},
            {"127.0.0.1|/dump/info;127.0.0.2|/dump/test", "", "127.0.0.1", "/dump/fail", "200", true}, // _whiteListByPath

            {"172.0.0.0-255", "", "127.0.0.1", "/", "403", true},
            {"172.0.0.0-255", "", "127.0.0.1", "/dump/info", "403", true},

            {"172.0.0.0-255|/dump/*;127.0.0.0-255|/dump/*", "", "127.0.0.1", "/", "200", true}, // _whiteListByPath
            {"172.0.0.0-255|/dump/*;127.0.0.0-255|/dump/*", "", "127.0.0.1", "/dispatch", "200", true}, // _whiteListByPath
            {"172.0.0.0-255|/dump/*;127.0.0.0-255|/dump/*", "", "127.0.0.1", "/dump/info", "200", true},
            };
        return Arrays.asList(data).stream().map(Arguments::of);
    }
}
