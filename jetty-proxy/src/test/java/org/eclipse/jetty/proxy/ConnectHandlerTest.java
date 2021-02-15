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

package org.eclipse.jetty.proxy;

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
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testCONNECTwithIPv6() throws Exception
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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + port + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
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
            protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address)
            {
                String proxyAuthorization = request.getHeader("Proxy-Authorization");
                if (proxyAuthorization == null)
                {
                    response.setHeader("Proxy-Authenticate", "Basic realm=\"test\"");
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
            assertEquals(HttpStatus.PROXY_AUTHENTICATION_REQUIRED_407, response.getStatus());
            assertTrue(response.containsKey("Proxy-Authenticate".toLowerCase(Locale.ENGLISH)));

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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
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
            StringBuilder err = new StringBuilder();
            err.append("DNS Hijacking detected: ");
            err.append(invalidHostname).append(" should have not returned a valid IP address [");
            err.append(address.getHostAddress()).append("].  ");
            err.append("Fix your DNS provider to have this test pass.");
            err.append("\nFor more info see https://en.wikipedia.org/wiki/DNS_hijacking");
            assertNull(address, err.toString());
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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // The pipelined request must have gone up to the server as is
            response = HttpTester.parseResponse(in);
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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            request =
                "GET /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
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
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("POST /echo\r\nHELLO", response.getContent());

            request =
                "GET /echo" + " HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "\r\n";
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            StringBuilder body = new StringBuilder();
            String chunk = "0123456789ABCDEF";
            for (int i = 0; i < 1024 * 1024; ++i)
            {
                body.append(chunk);
            }

            request =
                "POST /echo HTTP/1.1\r\n" +
                    "Host: " + hostPort + "\r\n" +
                    "Content-Length: " + body.length() + "\r\n" +
                    "\r\n" +
                    body;
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            response = HttpTester.parseResponse(in);
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
            protected boolean handleAuthentication(HttpServletRequest request, HttpServletResponse response, String address)
            {
                request.setAttribute(contextKey, contextValue);
                return super.handleAuthentication(request, response, address);
            }

            @Override
            protected void connectToServer(HttpServletRequest request, String host, int port, Promise<SocketChannel> promise)
            {
                assertEquals(contextValue, request.getAttribute(contextKey));
                super.connectToServer(request, host, port, promise);
            }

            @Override
            protected void prepareContext(HttpServletRequest request, ConcurrentMap<String, Object> context)
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
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // The pipelined request must have gone up to the server as is
            response = HttpTester.parseResponse(in);
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
            assertEquals(HttpStatus.OK_200, response.getStatus());
            assertEquals("GET /echo", response.getContent());
        }
    }

    private static class ServerHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);

            String uri = httpRequest.getRequestURI();
            switch (uri)
            {
                case "/echo":
                {
                    StringBuilder builder = new StringBuilder();
                    builder.append(httpRequest.getMethod()).append(" ").append(uri);
                    if (httpRequest.getQueryString() != null)
                        builder.append("?").append(httpRequest.getQueryString());

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    InputStream input = httpRequest.getInputStream();
                    int read;
                    while ((read = input.read()) >= 0)
                    {
                        baos.write(read);
                    }
                    baos.close();
                    byte[] bytes = baos.toByteArray();

                    ServletOutputStream output = httpResponse.getOutputStream();
                    if (bytes.length == 0)
                        output.print(builder.toString());
                    else
                        output.println(builder.toString());
                    output.write(bytes);
                    break;
                }
                case "/close":
                {
                    request.getHttpChannel().getEndPoint().close();
                    break;
                }
                default:
                {
                    throw new ServletException();
                }
            }
        }
    }
}
