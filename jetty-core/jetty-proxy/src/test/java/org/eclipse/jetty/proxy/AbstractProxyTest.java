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

package org.eclipse.jetty.proxy;

import java.util.List;
import java.util.function.Consumer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;

public class AbstractProxyTest
{
    public static List<HttpVersion> httpVersions()
    {
        return List.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2);
    }

    protected HttpConfiguration serverHttpConfig = new HttpConfiguration();
    protected HttpConfiguration proxyHttpConfig = new HttpConfiguration();
    protected Server server;
    protected ServerConnector serverConnector;
    protected Server proxy;
    protected ServerConnector proxyConnector;
    protected HttpClient client;

    protected void startClient() throws Exception
    {
        startClient(client -> {});
    }

    protected void startClient(Consumer<HttpClient> configurator) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, HttpClientConnectionFactory.HTTP11, new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client)));
        configurator.accept(client);
        client.start();
    }

    protected void startProxy(ProxyHandler handler) throws Exception
    {
        QueuedThreadPool proxyPool = new QueuedThreadPool();
        proxyPool.setName("proxy");
        proxy = new Server(proxyPool);
        proxyHttpConfig.setSendDateHeader(false);
        proxyHttpConfig.setSendServerVersion(false);
        proxyConnector = new ServerConnector(proxy, 1, 1, new HttpConnectionFactory(proxyHttpConfig), new HTTP2CServerConnectionFactory(proxyHttpConfig));
        proxy.addConnector(proxyConnector);
        proxy.setHandler(handler);
        proxy.start();
    }

    protected void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverPool = new QueuedThreadPool();
        serverPool.setName("server");
        server = new Server(serverPool);
        serverConnector = new ServerConnector(server, 1, 1, new HttpConnectionFactory(serverHttpConfig), new HTTP2CServerConnectionFactory(serverHttpConfig));
        server.addConnector(serverConnector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(proxy);
        LifeCycle.stop(server);
    }
}
