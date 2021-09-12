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

package org.eclipse.jetty.quic.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>The client specific implementation of {@link QuicSession}.</p>
 * <p>When asked to create a QUIC stream, it creates a {@link QuicStreamEndPoint}
 * with an associated {@link Connection} created from the {@link ClientConnectionFactory},
 * retrieved from the connection context map.</p>
 */
public class ClientQuicSession extends QuicSession
{
    private final Map<String, Object> context;

    protected ClientQuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicheConnection quicheConnection, QuicConnection connection, InetSocketAddress remoteAddress, Map<String, Object> context)
    {
        super(executor, scheduler, byteBufferPool, quicheConnection, connection, remoteAddress);
        this.context = context;
    }

    @Override
    protected ProtocolSession createProtocolSession()
    {
        ClientConnectionFactory connectionFactory = (ClientConnectionFactory)context.get(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY);
        if (connectionFactory instanceof ProtocolSession.Factory)
            return ((ProtocolSession.Factory)connectionFactory).newProtocolSession(this, context);
        return new ClientProtocolSession(this);
    }

    @Override
    public Connection newConnection(QuicStreamEndPoint endPoint)
    {
        try
        {
            ClientConnectionFactory connectionFactory = (ClientConnectionFactory)context.get(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY);
            return connectionFactory.newConnection(endPoint, context);
        }
        catch (IOException x)
        {
            throw new RuntimeIOException(x);
        }
    }
}
