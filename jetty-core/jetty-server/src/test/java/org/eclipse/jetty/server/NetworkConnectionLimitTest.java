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

package org.eclipse.jetty.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class NetworkConnectionLimitTest
{
    private Server server;
    private ServerConnector connector;

    private void prepare(int acceptors, Handler handler)
    {
        if (server == null)
            server = new Server();
        connector = new ServerConnector(server, acceptors, 1);
        server.addConnector(connector);
        server.setHandler(handler);
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    public void testNetworkConnectionLimitWithConnector(int acceptors) throws Exception
    {
        prepare(acceptors, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        int maxConnections = 2;
        NetworkConnectionLimit limiter = new NetworkConnectionLimit(maxConnections, connector);
        connector.addBean(limiter);
        server.start();

        List<SocketChannel> channels = new ArrayList<>();
        for (int i = 0; i < maxConnections; ++i)
        {
            SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort()));
            channels.add(channel);
        }
        // On the client connections may be accepted, but on server not yet.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(maxConnections, limiter.getNetworkConnectionCount()));
        // The limit was reached.
        assertFalse(connector.isAccepting());

        // An extra connection is accepted at the TCP level, but not notified to the JVM yet:
        // it remains in the connector accept queue, which cannot be configured to be zero.
        List<SocketChannel> extraChannels = new ArrayList<>();
        for (int i = 0; i < 2; ++i)
        {
            SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort()));
            extraChannels.add(channel);
        }
        await().during(1, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(maxConnections, limiter.getNetworkConnectionCount()));

        // Closing one existing connection may accept
        // all the extra connections when acceptors=0.
        channels.remove(0).close();
        // Verify that we are still correctly limited
        // and that we have accepted a pending connection.
        await().atMost(5, TimeUnit.SECONDS).during(1, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(maxConnections, limiter.getNetworkConnectionCount()));

        extraChannels.forEach(IO::close);
        channels.forEach(IO::close);
    }

    @Test
    public void testNetworkConnectionLimitWithServer() throws Exception
    {
        prepare(1, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        ServerConnector connector2 = new ServerConnector(server, 0, 1);
        server.addConnector(connector2);
        int maxConnections = 2;
        NetworkConnectionLimit limiter = new NetworkConnectionLimit(maxConnections, server);
        server.addBean(limiter);
        server.start();

        // Max out the connections.
        SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort()));
        SocketChannel.open(new InetSocketAddress("localhost", connector2.getLocalPort()));
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(maxConnections, limiter.getNetworkConnectionCount()));

        // Try to create more, should not be possible.
        SocketChannel extraChannel1 = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort()));
        await().during(1, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(maxConnections, limiter.getNetworkConnectionCount()));
        SocketChannel extraChannel2 = SocketChannel.open(new InetSocketAddress("localhost", connector2.getLocalPort()));
        await().during(1, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(maxConnections, limiter.getNetworkConnectionCount()));

        extraChannel2.close();
        extraChannel1.close();
    }

    @Test
    public void testAcceptRejectedByExecutor() throws Exception
    {
        // One acceptor, one selector, one application.
        int maxThreads = 3;
        int maxQueue = 1;
        QueuedThreadPool serverThreads = new QueuedThreadPool(maxThreads, 0, new ArrayBlockingQueue<>(maxQueue));
        serverThreads.setReservedThreads(0);
        serverThreads.setDetailedDump(true);
        server = new Server(serverThreads);
        prepare(1, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });
        int maxConnections = 2;
        NetworkConnectionLimit limiter = new NetworkConnectionLimit(maxConnections, connector);
        connector.addBean(limiter);
        server.start();

        // Block the last thread.
        CompletableFuture<Void> blocker = new CompletableFuture<>();
        serverThreads.execute(blocker::join);
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(maxThreads, serverThreads.getThreads()));

        // Fill the thread pool queue.
        IntStream.range(0, maxQueue).forEach(i -> serverThreads.execute(() ->
        {
        }));

        // Try to connect, the accept task should be rejected.
        try (SocketChannel channel = SocketChannel.open(new InetSocketAddress("localhost", connector.getLocalPort())))
        {
            ByteBuffer byteBuffer = ByteBuffer.allocate(16);
            assertEquals(-1, channel.read(byteBuffer));
            await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> assertEquals(0, limiter.getPendingNetworkConnectionCount()));
        }

        // Release the blocked task.
        blocker.complete(null);
    }
}
