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
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientChunkedContentTest
{
    private HttpClient client;

    private void startClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient();
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
    public void testServerHeadersPauseTerminalClientResponse() throws Exception
    {
        startClient();

        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress("localhost", 0));

            final AtomicReference<Result> resultRef = new AtomicReference<>();
            final CountDownLatch completeLatch = new CountDownLatch(1);
            client.newRequest("localhost", server.getLocalPort())
                .timeout(5, TimeUnit.SECONDS)
                .send(result ->
                {
                    resultRef.set(result);
                    completeLatch.countDown();
                });

            try (Socket socket = server.accept())
            {
                consumeRequestHeaders(socket);

                OutputStream output = socket.getOutputStream();
                String headers =
                    "HTTP/1.1 200 OK\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n";
                output.write(headers.getBytes(StandardCharsets.UTF_8));
                output.flush();

                Thread.sleep(1000);

                String terminal =
                    "0\r\n" +
                        "\r\n";
                output.write(terminal.getBytes(StandardCharsets.UTF_8));
                output.flush();

                assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
                Result result = resultRef.get();
                assertTrue(result.isSucceeded());
                Response response = result.getResponse();
                assertEquals(200, response.getStatus());
            }
        }
    }

    @Test
    public void testServerContentTerminalClientContentDelay() throws Exception
    {
        startClient();

        try (ServerSocket server = new ServerSocket())
        {
            server.bind(new InetSocketAddress("localhost", 0));

            final AtomicReference<Runnable> demanderRef = new AtomicReference<>();
            final CountDownLatch firstContentLatch = new CountDownLatch(1);
            final AtomicReference<Result> resultRef = new AtomicReference<>();
            final CountDownLatch completeLatch = new CountDownLatch(1);
            client.newRequest("localhost", server.getLocalPort())
                .onResponseContentAsync((response, chunk, demander) ->
                {
                    chunk.release();
                    if (demanderRef.compareAndSet(null, demander))
                        firstContentLatch.countDown();
                    else
                        demander.run();
                })
                .timeout(5, TimeUnit.SECONDS)
                .send(result ->
                {
                    resultRef.set(result);
                    completeLatch.countDown();
                });

            try (Socket socket = server.accept())
            {
                consumeRequestHeaders(socket);

                OutputStream output = socket.getOutputStream();
                String response =
                    "HTTP/1.1 200 OK\r\n" +
                        "Transfer-Encoding: chunked\r\n" +
                        "\r\n" +
                        "8\r\n" +
                        "01234567\r\n" +
                        "0\r\n" +
                        "\r\n";
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();

                // Simulate a delay in consuming the content.
                assertTrue(firstContentLatch.await(5, TimeUnit.SECONDS));
                Thread.sleep(1000);
                demanderRef.get().run();

                // Wait for the client to read 0 and become idle.
                Thread.sleep(1000);

                assertTrue(completeLatch.await(5, TimeUnit.SECONDS));
                Result result = resultRef.get();
                assertTrue(result.isSucceeded());
                assertEquals(200, result.getResponse().getStatus());

                // Issue another request to be sure the connection is sane.
                Request request = client.newRequest("localhost", server.getLocalPort())
                    .timeout(5, TimeUnit.SECONDS);
                FutureResponseListener listener = new FutureResponseListener(request);
                request.send(listener);

                consumeRequestHeaders(socket);
                output.write(response.getBytes(StandardCharsets.UTF_8));
                output.flush();

                assertEquals(200, listener.get(5, TimeUnit.SECONDS).getStatus());
            }
        }
    }

    private void consumeRequestHeaders(Socket socket) throws IOException
    {
        InputStream input = socket.getInputStream();
        int crlfs = 0;
        while (true)
        {
            int read = input.read();
            if (read == '\r' || read == '\n')
                ++crlfs;
            else
                crlfs = 0;
            if (crlfs == 4)
                break;
        }
    }
}
