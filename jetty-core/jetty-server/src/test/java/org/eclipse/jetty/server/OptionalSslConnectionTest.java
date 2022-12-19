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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class OptionalSslConnectionTest
{
    private Server server;
    private ServerConnector connector;

    private void startServer(Function<SslConnectionFactory, OptionalSslConnectionFactory> configFn, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConfiguration httpConfig = new HttpConfiguration();
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        OptionalSslConnectionFactory sslOrOther = configFn.apply(ssl);
        connector = new ServerConnector(server, 1, 1, sslOrOther, http);
        server.addConnector(connector);

        server.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        if (server != null)
            server.stop();
    }

    private OptionalSslConnectionFactory optionalSsl(SslConnectionFactory ssl)
    {
        return new OptionalSslConnectionFactory(ssl, ssl.getNextProtocol());
    }

    private OptionalSslConnectionFactory optionalSslNoOtherProtocol(SslConnectionFactory ssl)
    {
        return new OptionalSslConnectionFactory(ssl, null);
    }

    @Test
    public void testOptionalSslConnection() throws Exception
    {
        startServer(this::optionalSsl, new EmptyServerHandler());

        String request =
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        byte[] requestBytes = request.getBytes(StandardCharsets.US_ASCII);

        // Try first a plain text connection.
        try (Socket plain = new Socket())
        {
            plain.connect(new InetSocketAddress("localhost", connector.getLocalPort()), 1000);
            OutputStream plainOutput = plain.getOutputStream();
            plainOutput.write(requestBytes);
            plainOutput.flush();

            plain.setSoTimeout(5000);
            InputStream plainInput = plain.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(plainInput);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }

        // Then try an SSL connection.
        SslContextFactory sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.start();
        try (Socket ssl = sslContextFactory.newSslSocket())
        {
            ssl.connect(new InetSocketAddress("localhost", connector.getLocalPort()), 1000);
            OutputStream sslOutput = ssl.getOutputStream();
            sslOutput.write(requestBytes);
            sslOutput.flush();

            ssl.setSoTimeout(5000);
            InputStream sslInput = ssl.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(sslInput);
            assertNotNull(response);
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
        finally
        {
            sslContextFactory.stop();
        }
    }

    @Test
    public void testOptionalSslConnectionWithOnlyOneByteShouldIdleTimeout() throws Exception
    {
        startServer(this::optionalSsl, new EmptyServerHandler());
        long idleTimeout = 1000;
        connector.setIdleTimeout(idleTimeout);

        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress("localhost", connector.getLocalPort()), 1000);
            OutputStream output = socket.getOutputStream();
            output.write(0x16);
            output.flush();

            socket.setSoTimeout((int)(2 * idleTimeout));
            InputStream input = socket.getInputStream();
            int read = input.read();
            assertEquals(-1, read);
        }
    }

    @Test
    public void testOptionalSslConnectionWithUnknownBytes() throws Exception
    {
        startServer(this::optionalSslNoOtherProtocol, new EmptyServerHandler());

        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress("localhost", connector.getLocalPort()), 1000);
            OutputStream output = socket.getOutputStream();
            output.write(0x00);
            output.flush();
            Thread.sleep(500);
            output.write(0x00);
            output.flush();

            socket.setSoTimeout(5000);
            InputStream input = socket.getInputStream();
            int read = input.read();
            assertEquals(-1, read);
        }
    }

    @Test
    public void testOptionalSslConnectionWithHTTPBytes() throws Exception
    {
        startServer(this::optionalSslNoOtherProtocol, new EmptyServerHandler());

        String request =
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        byte[] requestBytes = request.getBytes(StandardCharsets.US_ASCII);

        // Send a plain text HTTP request to SSL port,
        // we should get back a minimal HTTP response.
        try (Socket socket = new Socket())
        {
            socket.connect(new InetSocketAddress("localhost", connector.getLocalPort()), 1000);
            OutputStream output = socket.getOutputStream();
            output.write(requestBytes);
            output.flush();

            socket.setSoTimeout(5000);
            InputStream input = socket.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(input);
            assertNotNull(response);
            assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
        }
    }

    @Test
    public void testNextProtocolIsNotNullButNotConfiguredEither() throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        String keystore = MavenTestingUtils.getTestResourceFile("keystore.p12").getAbsolutePath();
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConfiguration httpConfig = new HttpConfiguration();
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        OptionalSslConnectionFactory optSsl = new OptionalSslConnectionFactory(ssl, "no-such-protocol");
        connector = new ServerConnector(server, 1, 1, optSsl, http);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        try (Socket socket = new Socket(server.getURI().getHost(), server.getURI().getPort());
             StacklessLogging ignored = new StacklessLogging(DetectorConnectionFactory.class))
        {
            OutputStream sslOutput = socket.getOutputStream();
            String request =
                "GET / HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n";
            byte[] requestBytes = request.getBytes(StandardCharsets.US_ASCII);

            sslOutput.write(requestBytes);
            sslOutput.flush();

            socket.setSoTimeout(5000);
            InputStream sslInput = socket.getInputStream();
            HttpTester.Response response = HttpTester.parseResponse(sslInput);
            assertNull(response);
        }
    }

    private static class EmptyServerHandler extends Handler.Abstract
    {
        @Override
        public boolean process(Request request, Response response, Callback callback) throws Exception
        {
            callback.succeeded();
            return true;
        }
    }
}
