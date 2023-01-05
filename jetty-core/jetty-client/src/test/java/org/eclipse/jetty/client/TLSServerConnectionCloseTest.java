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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TLSServerConnectionCloseTest
{
    private HttpClient client;

    private void startClient() throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);

        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        clientConnector.setSslContextFactory(sslContextFactory);

        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);

        client = new HttpClient(new HttpClientTransportOverHTTP(clientConnector));
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
    }

    @ParameterizedTest
    @EnumSource(CloseMode.class)
    public void testServerSendsConnectionCloseWithoutContent(CloseMode closeMode) throws Exception
    {
        testServerSendsConnectionClose(closeMode, false, "");
    }

    @ParameterizedTest
    @EnumSource(CloseMode.class)
    public void testServerSendsConnectionCloseWithContent(CloseMode closeMode) throws Exception
    {
        testServerSendsConnectionClose(closeMode, false, "data");
    }

    @ParameterizedTest
    @EnumSource(CloseMode.class)
    public void testServerSendsConnectionCloseWithChunkedContent(CloseMode closeMode) throws Exception
    {
        testServerSendsConnectionClose(closeMode, true, "data");
    }

    private void testServerSendsConnectionClose(final CloseMode closeMode, boolean chunked, String content) throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            int port = server.getLocalPort();

            startClient();

            Request request = client.newRequest("localhost", port).scheme("https").path("/ctx/path");
            FutureResponseListener listener = new FutureResponseListener(request);
            request.send(listener);

            try (Socket socket = server.accept())
            {
                SSLContext sslContext = client.getSslContextFactory().getSslContext();
                SSLSocket sslSocket = (SSLSocket)sslContext.getSocketFactory().createSocket(socket, "localhost", port, false);
                sslSocket.setUseClientMode(false);
                sslSocket.startHandshake();

                InputStream input = sslSocket.getInputStream();
                consumeRequest(input);

                OutputStream output = sslSocket.getOutputStream();
                String serverResponse =
                    "HTTP/1.1 200 OK\r\n" +
                        "Connection: close\r\n";
                if (chunked)
                {
                    serverResponse +=
                        "Transfer-Encoding: chunked\r\n" +
                            "\r\n";
                    for (int i = 0; i < 2; ++i)
                    {
                        serverResponse +=
                            Integer.toHexString(content.length()) + "\r\n" +
                                content + "\r\n";
                    }
                    serverResponse +=
                        "0\r\n" +
                            "\r\n";
                }
                else
                {
                    serverResponse += "Content-Length: " + content.length() + "\r\n";
                    serverResponse += "\r\n";
                    serverResponse += content;
                }

                output.write(serverResponse.getBytes(StandardCharsets.UTF_8));
                output.flush();

                switch (closeMode)
                {
                    case NONE:
                    {
                        break;
                    }
                    case CLOSE:
                    {
                        sslSocket.close();
                        break;
                    }
                    case ABRUPT:
                    {
                        socket.shutdownOutput();
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }

                ContentResponse response = listener.get(5, TimeUnit.SECONDS);
                assertEquals(HttpStatus.OK_200, response.getStatus());

                // Give some time to process the connection.
                Thread.sleep(1000);

                // Connection should have been removed from pool.
                HttpDestination destination = (HttpDestination)client.resolveDestination(request);
                DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
                assertEquals(0, connectionPool.getConnectionCount());
                assertEquals(0, connectionPool.getIdleConnectionCount());
                assertEquals(0, connectionPool.getActiveConnectionCount());
            }
        }
    }

    private boolean consumeRequest(InputStream input) throws IOException
    {
        int crlfs = 0;
        while (true)
        {
            int read = input.read();
            if (read < 0)
                return true;
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                return false;
        }
    }

    private enum CloseMode
    {
        NONE, CLOSE, ABRUPT
    }
}
