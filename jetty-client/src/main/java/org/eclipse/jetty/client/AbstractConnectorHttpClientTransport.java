//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Map;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connectable;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;

@ManagedObject
public abstract class AbstractConnectorHttpClientTransport extends AbstractHttpClientTransport
{
    private final Connectable connector;

    protected AbstractConnectorHttpClientTransport(Connectable connector)
    {
        this.connector = connector;
        addBean(connector);
    }

    public ClientConnector getClientConnector()
    {
        ClientConnector result = null;
        if (connector instanceof ClientConnector)
            result = (ClientConnector)connector;
        else if (connector instanceof Container)
            result = ((Container)connector).getContainedBeans(ClientConnector.class).stream().findFirst().orElse(null);
        if (result == null)
            throw new IllegalArgumentException(ClientConnector.class.getName() + " not found in transport " + this);
        return result;
    }

    @ManagedAttribute(value = "The number of selectors", readonly = true)
    public int getSelectors()
    {
        return getClientConnector().getSelectors();
    }

    @Override
    public void connect(InetSocketAddress address, Map<String, Object> context)
    {
        HttpDestination destination = (HttpDestination)context.get(HTTP_DESTINATION_CONTEXT_KEY);
        context.put(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY, destination.getClientConnectionFactory());
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
        context.put(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY, Promise.from(ioConnection -> {}, promise::failed));
        connector.connect(address, context);
    }
}
