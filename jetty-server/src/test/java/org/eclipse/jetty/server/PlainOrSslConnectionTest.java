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

package org.eclipse.jetty.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PlainOrSslConnectionTest
{
    private Server server;
    private ServerConnector connector;

    private void startServer(Function<SslConnectionFactory, PlainOrSslConnectionFactory> configFn, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        String keystore = MavenTestingUtils.getTestResourceFile("keystore").getAbsolutePath();
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath(keystore);
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setKeyManagerPassword("keypwd");

        HttpConfiguration httpConfig = new HttpConfiguration();
        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, http.getProtocol());
        PlainOrSslConnectionFactory plainOrSsl = configFn.apply(ssl);
        connector = new ServerConnector(server, 1, 1, plainOrSsl, ssl, http);
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

    private PlainOrSslConnectionFactory plainOrSsl(SslConnectionFactory ssl)
    {
        return new PlainOrSslConnectionFactory(ssl, ssl.getNextProtocol());
    }

    private PlainOrSslConnectionFactory plainToSslWithReport(SslConnectionFactory ssl)
    {
        return new PlainOrSslConnectionFactory(ssl, null)
        {
            @Override
            protected void unknownProtocol(ByteBuffer buffer, EndPoint endPoint)
            {
                String response = "" +
                        "HTTP/1.1 400 Bad Request\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";
                Callback.Completable callback = new Callback.Completable();
                endPoint.write(callback, ByteBuffer.wrap(response.getBytes(StandardCharsets.US_ASCII)));
                callback.whenComplete((r, x) -> endPoint.close());
            }
        };
    }

    @Test
    public void testPlainOrSslConnection() throws Exception
    {
        startServer(this::plainOrSsl, new EmptyServerHandler());

        String request = "" +
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

        // Then try a SSL connection.
        SslContextFactory sslContextFactory = new SslContextFactory(true);
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
    public void testPlainToSslWithReport() throws Exception
    {
        startServer(this::plainToSslWithReport, new EmptyServerHandler());

        String request = "" +
                "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n";
        byte[] requestBytes = request.getBytes(StandardCharsets.US_ASCII);

        // Send a plain text HTTP request to SSL port: we should get back a minimal HTTP response.
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
            assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
        }
    }

    private static class EmptyServerHandler extends AbstractHandler.ErrorDispatchHandler
    {
        @Override
        protected void doNonErrorHandle(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
        {
            jettyRequest.setHandled(true);
        }
    }
}
