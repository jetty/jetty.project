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

package org.eclipse.jetty.ee10.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ConcurrentMap;

import jakarta.servlet.ServletException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ConnectHandler;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectHandlerTest extends AbstractConnectHandlerTest
{
    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        serverConnector = new ServerConnector(server);
        server.addConnector(serverConnector);
        server.setHandler(new ServerHandler());
        server.start();
        prepareProxy();
    }

    @Test
    public void testCONNECT() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testCONNECTWithIPv6() throws Exception
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        String hostPort = "[::1]:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testCONNECTAndGET() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    @Test
    public void testProxyWhiteList() throws Exception
    {
        int port = serverConnector.getLocalPort();
        String hostPort = "127.0.0.1:" + port;
        connectHandler.getWhiteListHosts().add(hostPort);

        // Try with the wrong host
        String request =
            "CONNECT localhost:" + port + " HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 403 from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

            // Socket should be closed
            assertEquals(-1, input.read());
        }

        // Try again with the right host
        request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    @Test
    public void testProxyBlackList() throws Exception
    {
        int port = serverConnector.getLocalPort();
        String hostPort = "localhost:" + port;
        connectHandler.getBlackListHosts().add(hostPort);

        // Try with the wrong host
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 403 from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.FORBIDDEN_403, response.getStatus());

            // Socket should be closed
            assertEquals(-1, input.read());
        }

        // Try again with the right host
        request =
            "CONNECT 127.0.0.1:" + port + " HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + port + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + port + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    @Test
    public void testProxyAuthentication() throws Exception
    {
        disposeProxy();
        connectHandler = new ConnectHandler()
        {
            @Override
            protected boolean handleAuthentication(Request request, Response response, String address)
            {
                String proxyAuthorization = request.getHeaders().get("Proxy-Authorization");
                if (proxyAuthorization == null)
                {
                    response.getHeaders().put("Proxy-Authenticate", "Basic realm=\"test\"");
                    return false;
                }
                String b64 = proxyAuthorization.substring("Basic ".length());
                String credentials = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                return "test:test".equals(credentials);
            }
        };
        proxy.setHandler(connectHandler);
        proxy.start();

        int port = serverConnector.getLocalPort();
        String hostPort = "localhost:" + port;

        // Try without authentication
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 407 from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407, response.getStatus());
            assertTrue(response.contains("Proxy-Authenticate".toLowerCase(Locale.ENGLISH)));

            // Socket should be closed
            assertEquals(-1, input.read());
        }

        // Try with authentication
        String credentials = "Basic " + Base64.getEncoder().encodeToString("test:test".getBytes(ISO_8859_1));
        request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "Proxy-Authorization: " + credentials + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    @Test
    public void testCONNECTBadHostPort() throws Exception
    {
        String invalidHostname = "badHost.webtide.com";

        try
        {
            InetAddress address = InetAddress.getByName(invalidHostname);
            String err = """
                DNS Hijacking detected: %s should have not returned a valid IP address [%s].
                Fix your DNS provider to have this test pass.
                For more info see https://en.wikipedia.org/wiki/DNS_hijacking")
                """.formatted(invalidHostname, address.getHostAddress());
            assertNull(address, err);
        }
        catch (UnknownHostException e)
        {
            // expected path
        }

        String hostPort = String.format("%s:%d", invalidHostname, serverConnector.getLocalPort());
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            socket.setSoTimeout(30000);
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 500 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR_500, response.getStatus(), "Response Code");
        }
    }

    @Test
    public void testCONNECT10AndGET() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.0\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    @Test
    public void testCONNECTAndGETPipelined() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n" +
                "GET /echo" + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // The pipelined request must have gone up to the server as is
            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    @Test
    public void testCONNECTAndMultipleGETs() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            for (int i = 0; i < 10; ++i)
            {
                request =
                    "GET /echo" + " HTTP/1.1\r\n" +
                        "Host: " + hostPort + "\r\n" +
                        "\r\n";
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                response = HttpTester.parseResponse(in);
                assertNotNull(response);
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertEquals("GET /echo", response.getContent());
            }
        }
    }

    @Test
    public void testCONNECTAndGETServerStop() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());

            // Idle server is shut down
            disposeServer();

            int read = input.read();
            assertEquals(-1, read);
        }
    }

    @Test
    public void testCONNECTAndGETAndServerSideClose() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /close HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            int read = input.read();
            assertEquals(-1, read);
        }
    }

    @Test
    public void testCONNECTAndPOSTAndGET() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: 5\r\n" +
                    "\r\n" +
                    "HELLO";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("POST /echo\r\nHELLO", response.getContent());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    @Test
    public void testCONNECTAndPOSTWithBigBody() throws Exception
    {
        // Use a longer idle timeout since this test
        // may take a long time on slower machines.
        long idleTimeout = 5 * 60 * 1000;
        serverConnector.setIdleTimeout(idleTimeout);
        proxyConnector.setIdleTimeout(idleTimeout);
        connectHandler.setIdleTimeout(idleTimeout);

        String hostPort = "localhost:" + serverConnector.getLocalPort();

        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            socket.setSoTimeout((int)idleTimeout);
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            String chunk = "0123456789ABCDEF";
            String body = chunk.repeat(1024 * 1024);

            request =
                "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "\r\n" +
                    body;
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("POST /echo\r\n" + body, response.getContent());
        }
    }

    @Test
    public void testCONNECTAndPOSTWithContext() throws Exception
    {
        final String contextKey = "contextKey";
        final String contextValue = "contextValue";

        // Replace the default ProxyHandler with a subclass to test context information passing
        disposeProxy();
        proxy.setHandler(new ConnectHandler()
        {
            @Override
            protected boolean handleAuthentication(Request request, Response response, String address)
            {
                request.setAttribute(contextKey, contextValue);
                return super.handleAuthentication(request, response, address);
            }

            @Override
            protected void connectToServer(Request request, String host, int port, Promise<SocketChannel> promise)
            {
                assertEquals(contextValue, request.getAttribute(contextKey));
                super.connectToServer(request, host, port, promise);
            }

            @Override
            protected void prepareContext(Request request, ConcurrentMap<String, Object> context)
            {
                // Transfer data from the HTTP request to the connection context
                assertEquals(contextValue, request.getAttribute(contextKey));
                context.put(contextKey, request.getAttribute(contextKey));
            }

            @Override
            protected int read(EndPoint endPoint, ByteBuffer buffer, ConcurrentMap<String, Object> context) throws IOException
            {
                assertEquals(contextValue, context.get(contextKey));
                return super.read(endPoint, buffer, context);
            }

            @Override
            protected void write(EndPoint endPoint, ByteBuffer buffer, Callback callback, ConcurrentMap<String, Object> context)
            {
                assertEquals(contextValue, context.get(contextKey));
                super.write(endPoint, buffer, callback, context);
            }
        });
        proxy.start();

        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            String body = "0123456789ABCDEF";
            request =
                "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "\r\n" +
                    body;
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("POST /echo\r\n" + body, response.getContent());
        }
    }

    @Test
    public void testCONNECTAndGETPipelinedAndOutputShutdown() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n" +
                "GET /echo" + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
            socket.shutdownOutput();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // The pipelined request must have gone up to the server as is
            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    @Test
    public void testCONNECTAndGETAndOutputShutdown() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request =
            "CONNECT " + hostPort + " HTTP/1.1\r\n" +
                "Host: " + hostPort + "\r\n" +
                "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Input in = HttpTester.from(input);
            HttpTester.Response response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
            socket.shutdownOutput();

            // The pipelined request must have gone up to the server as is
            response = HttpTester.parseResponse(in);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    private static class ServerHandler extends Handler.Processor
    {
        @Override
        public void doProcess(Request request, Response response, Callback callback) throws Exception
        {
            String cp = Request.getPathInContext(request);
            switch (cp)
            {
                case "/echo" ->
                {
                    StringBuilder builder = new StringBuilder();
                    builder.append(request.getMethod()).append(" ").append(cp);
                    String query = request.getHttpURI().getQuery();

                    if (query != null)
                        builder.append("?").append(query);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    InputStream input = Request.asInputStream(request);
                    int read;
                    while ((read = input.read()) >= 0)
                    {
                        baos.write(read);
                    }
                    baos.close();
                    byte[] bytes = baos.toByteArray();

                    if (bytes.length == 0)
                    {
                        Content.Sink.write(response, true, builder.toString(), callback);
                    }
                    else
                    {
                        builder.append("\r\n");
                        Callback.Completable.with(c -> Content.Sink.write(response, false, builder.toString(), c))
                            .whenComplete((r, x) ->
                            {
                                if (x != null)
                                    callback.failed(x);
                                else
                                    response.write(true, ByteBuffer.wrap(bytes), callback);
                            });
                    }
                }
                case "/close" ->
                {
                    request.getConnectionMetaData().getConnection().getEndPoint().close();
                    callback.succeeded();
                }
                default -> throw new ServletException();
            }
        }
    }
}
