//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.client;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;

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
        this.connector = connector;
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
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, destination.getClientConnectionFactory());
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, promise);
        connector.connect(address, context);
    }
}
