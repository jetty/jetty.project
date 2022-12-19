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

package org.eclipse.jetty.server;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyProtocolTest
{
    private Server server;
    private ServerConnector connector;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, new ProxyConnectionFactory(), new HttpConnectionFactory());
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
    public void testProxyProtocolV1() throws Exception
    {
        final String remoteAddr = "192.168.0.0";
        final int remotePort = 12345;
        start(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                SocketAddress addr = request.getConnectionMetaData().getRemoteSocketAddress();
                if (addr instanceof InetSocketAddress iAddr)
                {
                    if (iAddr.getHostString().equals(remoteAddr) && iAddr.getPort() == remotePort)
                        callback.succeeded();
                    else
                        callback.failed(new Throwable("wrong address"));
                }
                else
                {
                    callback.failed(new Throwable("no inet address"));
                }
                return true;
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
        final byte[] customE0 = new byte[] {1, 2};
        final byte[] customE1 = new byte[] {-1, -1, -1};

        start(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                if (validateEndPoint(request) &&
                    remoteAddr.equals(Request.getRemoteAddr(request)) &&
                    remotePort == Request.getRemotePort(request))
                    callback.succeeded();
                else
                    callback.failed(new Throwable());
                return true;
            }

            private boolean validateEndPoint(Request request) 
            {
                HttpConnection con = (HttpConnection)request.getAttribute(HttpConnection.class.getName());
                EndPoint endPoint = con.getEndPoint();
                ProxyConnectionFactory.ProxyEndPoint proxyEndPoint = (ProxyConnectionFactory.ProxyEndPoint)endPoint;
                return Arrays.equals(customE0, proxyEndPoint.getTLV(0xE0)) &&
                       Arrays.equals(customE1, proxyEndPoint.getTLV(0xE1)) &&
                       proxyEndPoint.getTLV(0xE2) == null;
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

                    // length of remaining header (4+4+2+2+3+6+5+6 = 32)
                    "0020" +

                    // uint32_t src_addr; uint32_t dst_addr; uint16_t src_port; uint16_t dst_port;
                    "C0A80001" +
                    "7f000001" +
                    "3039" +
                    "1F90" +

                    // NOOP value 0
                    "040000" +

                    // NOOP value ABCDEF
                    "040003ABCDEF" +

                    // Custom 0xEO {0x01,0x02}
                    "E000020102" +

                    // Custom 0xE1 {0xFF,0xFF,0xFF}
                    "E10003FFFFFF";

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
            assertThat(response1, startsWith("HTTP/1.1 200 "));
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
        start(new Handler.Abstract.NonBlocking()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
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
