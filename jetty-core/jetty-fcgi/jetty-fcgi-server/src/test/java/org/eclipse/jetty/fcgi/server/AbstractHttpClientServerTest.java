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

package org.eclipse.jetty.fcgi.server;

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.LeakTrackingConnectionPool;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.LeakDetector;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;

import static org.hamcrest.MatcherAssert.assertThat;

public abstract class AbstractHttpClientServerTest
{
    private ByteBufferPool serverBufferPool;
    protected ByteBufferPool clientBufferPool;
    private final AtomicLong connectionLeaks = new AtomicLong();
    protected Server server;
    protected ServerConnector connector;
    protected HttpClient client;
    protected String scheme = HttpScheme.HTTP.asString();

    public void start(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        ServerFCGIConnectionFactory fcgiConnectionFactory = new ServerFCGIConnectionFactory(new HttpConfiguration());
        // TODO: restore leak tracking.
        serverBufferPool = new ArrayByteBufferPool();
        connector = new ServerConnector(server, null, null, serverBufferPool,
            1, Math.max(1, ProcessorUtils.availableProcessors() / 2), fcgiConnectionFactory);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        // TODO: restore leak tracking.
        if (clientBufferPool == null)
            clientBufferPool = new ArrayByteBufferPool();
        clientConnector.setByteBufferPool(clientBufferPool);
        HttpClientTransport transport = new HttpClientTransportOverFCGI(clientConnector, "");
        transport.setConnectionPoolFactory(destination -> new LeakTrackingConnectionPool(destination, client.getMaxConnectionsPerDestination())
        {
            @Override
            protected void leaked(LeakDetector<Connection>.LeakInfo leakInfo)
            {
                connectionLeaks.incrementAndGet();
            }
        });
        client = new HttpClient(transport);
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        System.gc();

        assertThat("Connection Leaks", connectionLeaks.get(), Matchers.is(0L));

        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }
}
