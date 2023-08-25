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

import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.fcgi.client.transport.HttpClientTransportOverFCGI;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;

import static org.junit.jupiter.api.Assertions.fail;

public abstract class AbstractHttpClientServerTest
{
    private ArrayByteBufferPool.Tracking serverBufferPool;
    protected ArrayByteBufferPool.Tracking clientBufferPool;
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
        serverBufferPool = new ArrayByteBufferPool.Tracking();
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
        if (clientBufferPool == null)
            clientBufferPool = new ArrayByteBufferPool.Tracking();
        clientConnector.setByteBufferPool(clientBufferPool);
        HttpClientTransport transport = new HttpClientTransportOverFCGI(clientConnector, "");
        client = new HttpClient(transport);
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        try
        {
            if (serverBufferPool != null)
            {
                try
                {
                    Awaitility.await().atMost(3, TimeUnit.SECONDS).until(() -> serverBufferPool.getLeaks().size(), Matchers.is(0));
                }
                catch (Exception e)
                {
                    fail(e.getMessage() + "\n---\nServer Leaks: " + serverBufferPool.dumpLeaks() + "---\n");
                }
            }
            if (clientBufferPool != null)
            {
                try
                {
                    Awaitility.await().atMost(3, TimeUnit.SECONDS).until(() -> clientBufferPool.getLeaks().size(), Matchers.is(0));
                }
                catch (Exception e)
                {
                    fail(e.getMessage() + "\n---\nClient Leaks: " + clientBufferPool.dumpLeaks() + "---\n");
                }
            }
        }
        finally
        {
            LifeCycle.stop(client);
            LifeCycle.stop(server);
        }
    }
}
