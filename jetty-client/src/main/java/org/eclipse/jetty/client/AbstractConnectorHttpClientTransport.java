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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public abstract class AbstractConnectorHttpClientTransport extends AbstractHttpClientTransport
{
    private final ClientConnector connector;

    protected AbstractConnectorHttpClientTransport(ClientConnector connector)
    {
        this.connector = Objects.requireNonNull(connector);
        addBean(connector);
    }

    public ClientConnector getClientConnector()
    {
        return connector;
    }

    @ManagedAttribute(value = "The number of selectors", readonly = true)
    public int getSelectors()
    {
        return connector.getSelectors();
    }

    @Override
    protected void doStart() throws Exception
    {
        HttpClient httpClient = getHttpClient();
        connector.setBindAddress(httpClient.getBindAddress());
        connector.setByteBufferPool(httpClient.getByteBufferPool());
        connector.setConnectBlocking(httpClient.isConnectBlocking());
        connector.setConnectTimeout(Duration.ofMillis(httpClient.getConnectTimeout()));
        connector.setExecutor(httpClient.getExecutor());
        connector.setIdleTimeout(Duration.ofMillis(httpClient.getIdleTimeout()));
        connector.setScheduler(httpClient.getScheduler());
        connector.setSslContextFactory(httpClient.getSslContextFactory());
        super.doStart();
    }

    @Override
    public void connect(SocketAddress address, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, destination.getClientConnectionFactory());
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, promise::failed));
        connector.connect(address, context);
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        connect((SocketAddress)address, context);
    }
}
