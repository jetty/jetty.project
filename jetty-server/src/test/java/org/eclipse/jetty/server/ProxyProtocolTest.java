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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyProtocolTest
{
    private Server server;
    private ServerConnector connector;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        DetectorConnectionFactory optionalSsl = new DetectorConnectionFactory(new SslConnectionFactory());
        connector = new ServerConnector(server, new ProxyConnectionFactory(), optionalSsl, new HttpConnectionFactory());
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testNoProxyJustHttp() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                if (target.startsWith("/foo/"))
                {
                    response.setContentType("text/plain");
                    response.setCharacterEncoding("utf-8");
                    response.getWriter().println("from [/foo/]");
                    baseRequest.setHandled(true);
                }
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            try (OutputStream output = socket.getOutputStream();
                 InputStream input = socket.getInputStream())
            {
                String request1 =
                    "GET /foo/zed HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

                output.write(request1.getBytes(StandardCharsets.UTF_8));
                output.flush();

                HttpTester.Response response = HttpTester.parseResponse(input);
                assertThat("status", response.getStatus(), is(200));
                assertThat("Content", response.getContent(), containsString("from [/foo/]"));
            }
        }
    }

    @Test
    public void testProxyProtocolV1() throws Exception
    {
        final String remoteAddr = "192.168.0.0";
        final int remotePort = 12345;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (remoteAddr.equals(request.getRemoteAddr()) &&
                    remotePort == request.getRemotePort())
                    baseRequest.setHandled(true);
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String request1 =
                "PROXY TCP4 " + remoteAddr + " 127.0.0.0 " + remotePort + " 8080\r\n" +
                    "GET /1 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(request1.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String response1 = reader.readLine();
            assertTrue(response1.startsWith("HTTP/1.1 200 "));
            while (true)
            {
                if (reader.readLine().isEmpty())
                    break;
            }

            // Send a second request to verify that the proxied IP is retained.
            String request2 =
                "GET /2 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            output.write(request2.getBytes(StandardCharsets.UTF_8));
            output.flush();

            String response2 = reader.readLine();
            assertTrue(response2.startsWith("HTTP/1.1 200 "));
            while (true)
            {
                if (reader.readLine() == null)
                    break;
            }
        }
    }

    @Test
    public void testProxyProtocolV2() throws Exception
    {
        final String remoteAddr = "192.168.0.1";
        final int remotePort = 12345;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                if (remoteAddr.equals(request.getRemoteAddr()) &&
                    remotePort == request.getRemotePort())
                    baseRequest.setHandled(true);
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String proxy =
                // Preamble
                "0D0A0D0A000D0A515549540A" +

                    // V2, PROXY
                    "21" +

                    // 0x1 : AF_INET    0x1 : STREAM.  Address length is 2*4 + 2*2 = 12 bytes.
                    "11" +

                    // length of remaining header (4+4+2+2+6+3 = 21)
                    "0015" +

                    // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
                    "C0A80001" +
                    "7f000001" +
                    "3039" +
                    "1F90" +

                    // NOOP value 0
                    "040000" +

                    // NOOP value ABCDEF
                    "040003ABCDEF";

            String request1 =
                "GET /1 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(TypeUtil.fromHexString(proxy));
            output.write(request1.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String response1 = reader.readLine();
            assertTrue(response1.startsWith("HTTP/1.1 200 "));
            while (true)
            {
                if (reader.readLine().isEmpty())
                    break;
            }

            // Send a second request to verify that the proxied IP is retained.
            String request2 =
                "GET /2 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            output.write(request2.getBytes(StandardCharsets.UTF_8));
            output.flush();

            String response2 = reader.readLine();
            assertTrue(response2.startsWith("HTTP/1.1 200 "));
            while (true)
            {
                if (reader.readLine() == null)
                    break;
            }
        }
    }

    @Test
    public void testProxyProtocolV2Local() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
            }
        });

        try (Socket socket = new Socket("localhost", connector.getLocalPort()))
        {
            String proxy =
                // Preamble
                "0D0A0D0A000D0A515549540A" +

                    // V2, LOCAL
                    "20" +

                    // 0x1 : AF_INET    0x1 : STREAM.  Address length is 2*4 + 2*2 = 12 bytes.
                    "11" +

                    // length of remaining header (4+4+2+2+6+3 = 21)
                    "0015" +

                    // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
                    "C0A80001" +
                    "7f000001" +
                    "3039" +
                    "1F90" +

                    // NOOP value 0
                    "040000" +

                    // NOOP value ABCDEF
                    "040003ABCDEF";

            String request1 =
                "GET /1 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            OutputStream output = socket.getOutputStream();
            output.write(TypeUtil.fromHexString(proxy));
            output.write(request1.getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = socket.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String response1 = reader.readLine();
            assertTrue(response1.startsWith("HTTP/1.1 200 "));
            while (true)
            {
                if (reader.readLine().isEmpty())
                    break;
            }

            // Send a second request to verify that the proxied IP is retained.
            String request2 =
                "GET /2 HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: close\r\n" +
                    "\r\n";
            output.write(request2.getBytes(StandardCharsets.UTF_8));
            output.flush();

            String response2 = reader.readLine();
            assertTrue(response2.startsWith("HTTP/1.1 200 "));
            while (true)
            {
                if (reader.readLine() == null)
                    break;
            }
        }
    }
}
