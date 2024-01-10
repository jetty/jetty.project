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

import java.net.SocketAddress;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.TransportProtocol;
import org.eclipse.jetty.util.Promise;

/**
 * <p>A {@link TransportProtocol} suitable to be used when using a {@link MemoryConnector}.</p>
 */
public class MemoryTransportProtocol implements TransportProtocol
{
    private final MemoryConnector connector;

    public MemoryTransportProtocol(MemoryConnector connector)
    {
        this.connector = connector;
    }

    @Override
    public void connect(SocketAddress socketAddress, Map<String, Object> context)
    {
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
        try
        {
            EndPoint endPoint = connector.connect().getLocalEndPoint();
            ClientConnector clientConnector = (ClientConnector)context.get(ClientConnector.CLIENT_CONNECTOR_CONTEXT_KEY);
            endPoint.setIdleTimeout(clientConnector.getIdleTimeout().toMillis());

            // This instance may be nested inside other TransportProtocol instances.
            // Retrieve the outermost instance to call newConnection().
            TransportProtocol transportProtocol = (TransportProtocol)context.get(TransportProtocol.class.getName());
            Connection connection = transportProtocol.newConnection(endPoint, context);
            endPoint.setConnection(connection);

            endPoint.onOpen();
            connection.onOpen();

            // TODO: move this to Connection.onOpen(), see
            //  ClientSelectorManager.connectionOpened()
            promise.succeeded(connection);
        }
        catch (Throwable x)
        {
            promise.failed(x);
        }
    }

    @Override
    public SocketAddress getSocketAddress()
    {
        return connector.getLocalSocketAddress();
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(connector);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj instanceof MemoryTransportProtocol that)
            return Objects.equals(connector, that.connector);
        return false;
    }
}
