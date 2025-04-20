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

package org.eclipse.jetty.client;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

@ManagedObject
public abstract class AbstractConnectorHttpClientTransport extends AbstractHttpClientTransport
{
    private final ClientConnector connector;

    protected AbstractConnectorHttpClientTransport(ClientConnector connector)
    {
        this.connector = Objects.requireNonNull(connector);
        installBean(connector);
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
        getHttpClient().connect(address, context);
    }
}
