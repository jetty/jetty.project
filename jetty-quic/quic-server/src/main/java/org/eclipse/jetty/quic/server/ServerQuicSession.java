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

package org.eclipse.jetty.quic.server;

import java.net.SocketAddress;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.quic.common.ProtocolQuicSession;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>The server specific implementation of {@link QuicSession}.</p>
 * <p>When asked to create a QUIC stream, it creates a {@link QuicStreamEndPoint}
 * with an associated {@link Connection} created from the {@link ConnectionFactory},
 * retrieved from the server {@link Connector}, correspondent to the protocol
 * negotiated with the client (or the default protocol).</p>
 */
public class ServerQuicSession extends QuicSession
{
    private final Connector connector;

    protected ServerQuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicheConnection quicheConnection, QuicConnection connection, SocketAddress remoteAddress, Connector connector)
    {
        super(executor, scheduler, byteBufferPool, quicheConnection, connection, remoteAddress);
        this.connector = connector;
    }

    @Override
    protected ProtocolQuicSession createProtocolQuicSession()
    {
        ConnectionFactory connectionFactory = findConnectionFactory(getNegotiatedProtocol());
        if (connectionFactory instanceof ProtocolQuicSession.Factory)
            return ((ProtocolQuicSession.Factory)connectionFactory).newProtocolQuicSession(this);
        return new ProtocolQuicSession(this);
    }

    @Override
    protected QuicStreamEndPoint createQuicStreamEndPoint(long streamId)
    {
        ConnectionFactory connectionFactory = findConnectionFactory(getNegotiatedProtocol());
        QuicStreamEndPoint endPoint = new QuicStreamEndPoint(getScheduler(), this, streamId);
        Connection connection = connectionFactory.newConnection(connector, endPoint);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
        return endPoint;
    }

    private ConnectionFactory findConnectionFactory(String negotiatedProtocol)
    {
        ConnectionFactory connectionFactory = connector.getConnectionFactory(negotiatedProtocol);
        if (connectionFactory == null)
            connectionFactory = connector.getDefaultConnectionFactory();
        if (connectionFactory == null)
            throw new RuntimeIOException("No configured connection factory can handle protocol '" + negotiatedProtocol + "'");
        return connectionFactory;
    }
}
