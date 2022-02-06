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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.FutureResponseListener;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ServerConnectionCloseTest
{
    private HttpClient client;

    private void startClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient(new HttpClientTransportOverHTTP(1));
        client.setExecutor(clientThreads);
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
    }

    @Test
    public void testServerSendsConnectionCloseWithoutContent() throws Exception
    {
        testServerSendsConnectionClose(true, false, "");
    }

    @Test
    public void testServerSendsConnectionCloseWithContent() throws Exception
    {
        testServerSendsConnectionClose(true, false, "data");
    }

    @Test
    public void testServerSendsConnectionCloseWithChunkedContent() throws Exception
    {
        testServerSendsConnectionClose(true, true, "data");
    }

    @Test
    public void testServerSendsConnectionCloseWithoutContentButDoesNotClose() throws Exception
    {
        testServerSendsConnectionClose(false, false, "");
    }

    @Test
    public void testServerSendsConnectionCloseWithContentButDoesNotClose() throws Exception
    {
        testServerSendsConnectionClose(false, false, "data");
    }

    @Test
    public void testServerSendsConnectionCloseWithChunkedContentButDoesNotClose() throws Exception
    {
        testServerSendsConnectionClose(false, true, "data");
    }

    private void testServerSendsConnectionClose(boolean shutdownOutput, boolean chunked, String content) throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            int port = server.getLocalPort();

            startClient();

            Request request = client.newRequest("localhost", port).path("/ctx/path");
            FutureResponseListener listener = new FutureResponseListener(request);
            request.send(listener);

            try (Socket socket = server.accept())
            {
                InputStream input = socket.getInputStream();
                consumeRequest(input);

                OutputStream output = socket.getOutputStream();
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

                output.write(serverResponse.getBytes("UTF-8"));
                output.flush();
                if (shutdownOutput)
                    socket.shutdownOutput();

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
}
