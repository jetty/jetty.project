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

import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.http.HttpChannelOverHTTP;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientUploadDuringServerShutdownTest
{
    /**
     * A server used in conjunction with {@link ClientSide}.
     */
    public static class ServerSide
    {
        public static void main(String[] args) throws Exception
        {
            QueuedThreadPool serverThreads = new QueuedThreadPool();
            serverThreads.setName("server");
            Server server = new Server(serverThreads);
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(8888);
            server.addConnector(connector);
            server.setHandler(new EmptyServerHandler()
            {
                @Override
                protected void service(Request request, Response response) throws Throwable
                {
                    while (true)
                    {
                        Content.Chunk chunk = request.read();
                        if (chunk == null)
                        {
                            try (Blocker.Runnable blocker = _blocking.runnable())
                            {
                                request.demand(blocker);
                            }
                        }
                        else
                        {
                            if (chunk.hasRemaining())
                                chunk.release();
                            if (chunk.isLast())
                                break;
                            NanoTime.spinWait(TimeUnit.MICROSECONDS.toNanos(1));
                        }
                    }
                }
            });
            server.start();
        }
    }

    /**
     * An infinite loop of a client uploading content to the server.
     * The server may be killed while this client is running, and the
     * behavior should be that this client continues running, failing
     * exchanges while the server is down, but succeeding them when
     * the server is up and running.
     *
     * @see ServerSide
     */
    public static class ClientSide
    {
        public static void main(String[] args) throws Exception
        {
            QueuedThreadPool clientThreads = new QueuedThreadPool();
            clientThreads.setName("client");
            HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(2));
            client.setMaxConnectionsPerDestination(2);
            client.setIdleTimeout(10000);
            client.setExecutor(clientThreads);
            client.start();

            Random random = new Random();

            while (true)
            {
                int count = 1;
                final CountDownLatch latch = new CountDownLatch(count);
                for (int i = 0; i < count; ++i)
                {
                    int length = 16 * 1024 * 1024 + random.nextInt(16 * 1024 * 1024);
                    client.newRequest("localhost", 8888)
                            .body(new BytesRequestContent(new byte[length]))
                            .send(result -> latch.countDown());
                    long sleep = 1 + random.nextInt(10);
                    TimeUnit.MILLISECONDS.sleep(sleep);
                }
                latch.await();
            }
        }
    }

    @Test
    public void testUploadDuringServerShutdown() throws Exception
    {
        final AtomicReference<EndPoint> endPointRef = new AtomicReference<>();
        final CountDownLatch serverLatch = new CountDownLatch(1);
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        Server server = new Server(serverThreads);
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler()
        {
            @Override
            protected void service(Request request, Response response)
            {
                endPointRef.set(request.getConnectionMetaData().getConnection().getEndPoint());
                serverLatch.countDown();
            }
        });
        server.start();

        final AtomicBoolean afterSetup = new AtomicBoolean();
        final CountDownLatch sendLatch = new CountDownLatch(1);
        final CountDownLatch beginLatch = new CountDownLatch(1);
        final CountDownLatch associateLatch = new CountDownLatch(1);
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        HttpClient client = new HttpClient(new HttpClientTransportOverHTTP(1)
        {
            @Override
            public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context)
            {
                return new HttpConnectionOverHTTP(endPoint, context)
                {
                    @Override
                    protected HttpChannelOverHTTP newHttpChannel()
                    {
                        return new HttpChannelOverHTTP(this)
                        {
                            @Override
                            public void send()
                            {
                                if (afterSetup.get())
                                    associateLatch.countDown();
                                super.send();
                            }
                        };
                    }

                    @Override
                    protected void close(Throwable failure)
                    {
                        try
                        {
                            sendLatch.countDown();
                            beginLatch.await(5, TimeUnit.SECONDS);
                            super.close(failure);
                        }
                        catch (InterruptedException x)
                        {
                            x.printStackTrace();
                        }
                    }

                    @Override
                    protected void abort(Throwable failure)
                    {
                        try
                        {
                            associateLatch.await(5, TimeUnit.SECONDS);
                            super.abort(failure);
                        }
                        catch (InterruptedException x)
                        {
                            x.printStackTrace();
                        }
                    }
                };
            }
        });
        client.setIdleTimeout(10000);
        client.setExecutor(clientThreads);
        client.start();

        // Create one connection.
        client.newRequest("localhost", connector.getLocalPort()).send();
        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));

        afterSetup.set(true);
        Thread.sleep(1000);

        // Close the connection, so that the receiver is woken
        // up and will call HttpConnectionOverHTTP.close().
        EndPoint endPoint = endPointRef.get();
        endPoint.close();

        // Wait for close() so that the connection that
        // is being closed is used to send the request.
        assertTrue(sendLatch.await(5, TimeUnit.SECONDS));

        CountDownLatch completeLatch = new CountDownLatch(1);
        var request = client.newRequest("localhost", connector.getLocalPort())
            .timeout(10, TimeUnit.SECONDS)
            .onRequestBegin(r ->
            {
                try
                {
                    beginLatch.countDown();
                    completeLatch.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            });
        request.send(result -> completeLatch.countDown());

        assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

        HttpDestination destination = (HttpDestination)client.resolveDestination(request);
        DuplexConnectionPool pool = (DuplexConnectionPool)destination.getConnectionPool();
        assertEquals(0, pool.getConnectionCount());
        assertEquals(0, pool.getIdleConnections().size());
        assertEquals(0, pool.getActiveConnections().size());
    }
}
