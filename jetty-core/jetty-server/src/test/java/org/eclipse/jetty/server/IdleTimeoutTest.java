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
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;

public class IdleTimeoutTest
{
    private Server server;
    private ServerConnector connector;

    @BeforeEach
    public void prepare()
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testIdleTimeoutWhenCongested() throws Exception
    {
        long idleTimeout = 1000;
        HttpConnectionFactory h1 = new HttpConnectionFactory(new HttpConfiguration());
        connector = new ServerConnector(server, 1, 1, h1)
        {
            @Override
            protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
            {
                SocketChannelEndPoint endpoint = new SocketChannelEndPoint(channel, selectSet, key, getScheduler())
                {
                    @Override
                    public boolean flush(ByteBuffer... buffers)
                    {
                        // Fake TCP congestion.
                        return false;
                    }

                    @Override
                    protected void onIncompleteFlush()
                    {
                        // Do nothing here to avoid spin loop,
                        // since the network is actually writable,
                        // as we are only faking TCP congestion.
                    }
                };
                endpoint.setIdleTimeout(getIdleTimeout());
                return endpoint;
            }
        };
        connector.setIdleTimeout(idleTimeout);
        server.addConnector(connector);
        server.start();

        try (SocketChannel client = SocketChannel.open())
        {
            client.connect(new InetSocketAddress("localhost", connector.getLocalPort()));

            HttpTester.Request request = HttpTester.newRequest();
            client.write(request.generate());

            // The server never writes back anything, but should close the connection.
            client.configureBlocking(false);
            ByteBuffer inputBuffer = ByteBuffer.allocate(1024);
            await().atMost(Duration.ofSeconds(5)).until(() -> client.read(inputBuffer), is(-1));
            await().atMost(5, TimeUnit.SECONDS).until(() -> connector.getConnectedEndPoints().size(), is(0));
        }
    }
}
