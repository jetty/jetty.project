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

package org.eclipse.jetty.ee9.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ConnectHandlerSSLTest extends AbstractConnectHandlerTest
{
    private SslContextFactory.Server sslContextFactory;

    @BeforeEach
    public void prepare() throws Exception
    {
        sslContextFactory = new SslContextFactory.Server();
        String keyStorePath = MavenTestingUtils.getTestResourceFile("server_keystore.p12").getAbsolutePath();
        sslContextFactory.setKeyStorePath(keyStorePath);
        sslContextFactory.setKeyStorePassword("storepwd");
        server = new Server();
        serverConnector = new ServerConnector(server, sslContextFactory);
        server.addConnector(serverConnector);
        server.setHandler(new ServerHandler());
        server.start();
        prepareProxy();
    }

    @Test
    public void testGETRequest() throws Exception
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

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = wrapSocket(socket))
            {
                output = sslSocket.getOutputStream();

                request =
                    "GET /echo HTTP/1.1\r\n" +
                        "Host: " + hostPort + "\r\n" +
                        "\r\n";
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                response = HttpTester.parseResponse(HttpTester.from(sslSocket.getInputStream()));
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertEquals("GET /echo", response.getContent());
            }
        }
    }

    @Test
    public void testPOSTRequests() throws Exception
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

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = wrapSocket(socket))
            {
                output = sslSocket.getOutputStream();

                for (int i = 0; i < 10; ++i)
                {
                    request =
                        "POST /echo?param=" + i + " HTTP/1.1\r\n" +
                            "Host: " + hostPort + "\r\n" +
                            "Content-Length: 5\r\n" +
                            "\r\n" +
                            "HELLO";
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    response = HttpTester.parseResponse(HttpTester.from(sslSocket.getInputStream()));
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertEquals("POST /echo?param=" + i + "\r\nHELLO", response.getContent());
                }
            }
        }
    }

    private SSLSocket wrapSocket(Socket socket) throws Exception
    {
        SSLContext sslContext = sslContextFactory.getSslContext();
        SSLSocketFactory socketFactory = sslContext.getSocketFactory();
        SSLSocket sslSocket = (SSLSocket)socketFactory.createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        sslSocket.setUseClientMode(true);
        sslSocket.startHandshake();
        return sslSocket;
    }

    private static class ServerHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request request, HttpServletRequest httpRequest, HttpServletResponse httpResponse) throws IOException, ServletException
        {
            request.setHandled(true);

            String uri = httpRequest.getRequestURI();
            if ("/echo".equals(uri))
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
            }
            else
            {
                throw new ServletException();
            }
        }
    }
}
