//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ConnectHandlerSSLTest extends AbstractConnectHandlerTest
{
    private SslContextFactory.Server sslContextFactory;

    @BeforeEach
    public void prepare() throws Exception
    {
        sslContextFactory = new SslContextFactory.Server();
        Path keyStorePath = MavenTestingUtils.getTestResourcePath("keystore.p12").toAbsolutePath();
        sslContextFactory.setKeyStorePath(keyStorePath.toString());
        sslContextFactory.setKeyStorePassword("storepwd");
        server = new Server();
        serverConnector = new ServerConnector(server, 1, 1, sslContextFactory);
        server.addConnector(serverConnector);
        server.setHandler(new ServerHandler());
        server.start();
        prepareProxy();
    }

    @Test
    public void testGETRequest() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = """
                CONNECT $A HTTP/1.1\r
                Host: $A\r
                \r
                """.replace("$A", hostPort);
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = wrapSocket(socket))
            {
                output = sslSocket.getOutputStream();

                request = """
                    GET /echo HTTP/1.1\r
                    Host: $A\r
                    \r
                    """.replace("$A", hostPort);
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                response = HttpTester.parseResponse(HttpTester.from(sslSocket.getInputStream()));
                assertNotNull(response);
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertEquals("GET /echo", response.getContent());
            }
        }
    }

    @Test
    public void testPOSTRequests() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = """
                CONNECT $A HTTP/1.1\r
                Host: $A\r
                \r
                """.replace("$A", hostPort);
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = wrapSocket(socket))
            {
                output = sslSocket.getOutputStream();

                for (int i = 0; i < 10; ++i)
                {
                    request = """
                            POST /echo?param=$P HTTP/1.1\r
                            Host: $A\r
                            Content-Length: 5\r
                            \r
                            HELLO""".replace("$P", String.valueOf(i)).replace("$A", hostPort);
                    output.write(request.getBytes(StandardCharsets.UTF_8));
                    output.flush();

                    response = HttpTester.parseResponse(HttpTester.from(sslSocket.getInputStream()));
                    assertNotNull(response);
                    assertEquals(HttpStatus.OK_200, response.getStatus());
                    assertEquals("POST /echo?param=" + i + "\r\nHELLO", response.getContent());
                }
            }
        }
    }

    @Test
    public void testCONNECTWithConnectionCloseInRequest() throws Exception
    {
        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = """
                CONNECT $A HTTP/1.1\r
                Host: $A\r
                Connection: close\r
                \r
                """.replace("$A", hostPort);
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = wrapSocket(socket))
            {
                output = sslSocket.getOutputStream();

                request = """
                    GET /echo HTTP/1.1\r
                    Host: $A\r
                    \r
                    """.replace("$A", hostPort);
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                response = HttpTester.parseResponse(HttpTester.from(sslSocket.getInputStream()));
                assertNotNull(response);
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertEquals("GET /echo", response.getContent());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testCONNECTWithConnectionCloseInResponse(boolean requestConnectionClose) throws Exception
    {
        disposeProxy();
        connectHandler = new ConnectHandler()
        {
            @Override
            protected void onConnectSuccess(ConnectContext connectContext, UpstreamConnection upstreamConnection)
            {
                // Add Connection: close to the 200 response.
                connectContext.getResponse().getHeaders().put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
                super.onConnectSuccess(connectContext, upstreamConnection);
            }
        };
        proxy.setHandler(connectHandler);
        proxy.start();

        String hostPort = "localhost:" + serverConnector.getLocalPort();
        String request = """
            CONNECT $A HTTP/1.1\r
            Host: $A\r
            """.replace("$A", hostPort);
        if (requestConnectionClose)
            request += "Connection: close\r\n";
        request += "\r\n";
        try (Socket socket = newSocket())
        {
            OutputStream output = socket.getOutputStream();

            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();

            // Expect 200 OK from the CONNECT request
            HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(socket.getInputStream()));
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());

            // Upgrade the socket to SSL
            try (SSLSocket sslSocket = wrapSocket(socket))
            {
                output = sslSocket.getOutputStream();

                request = """
                    GET /echo HTTP/1.1\r
                    Host: $A\r
                    \r
                    """.replace("$A", hostPort);
                output.write(request.getBytes(StandardCharsets.UTF_8));
                output.flush();

                response = HttpTester.parseResponse(HttpTester.from(sslSocket.getInputStream()));
                assertNotNull(response);
                assertEquals(HttpStatus.OK_200, response.getStatus());
                assertEquals("GET /echo", response.getContent());
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

    private static class ServerHandler extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            String uri = Request.getPathInContext(request);
            if ("/echo".equals(uri))
            {
                StringBuilder builder = new StringBuilder();
                builder.append(request.getMethod()).append(" ").append(uri);
                String query = request.getHttpURI().getQuery();
                if (query != null)
                    builder.append("?").append(query);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream input = Content.Source.asInputStream(request);
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

                return true;
            }
            throw new IllegalStateException();
        }
    }
}
