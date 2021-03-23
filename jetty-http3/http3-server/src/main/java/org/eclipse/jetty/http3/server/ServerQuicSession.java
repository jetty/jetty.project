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

package org.eclipse.jetty.http3.server;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicSession;
import org.eclipse.jetty.http3.common.QuicStreamEndPoint;
import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.thread.Scheduler;

public class ServerQuicSession extends QuicSession
{
    private final Connector connector;

    protected ServerQuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicheConnectionId quicheConnectionId, QuicheConnection quicheConnection, QuicConnection connection, InetSocketAddress remoteAddress, Connector connector)
    {
        super(executor, scheduler, byteBufferPool, quicheConnectionId, quicheConnection, connection, remoteAddress);
        this.connector = connector;
    }

    @Override
    protected QuicStreamEndPoint createQuicStreamEndPoint(long streamId)
    {
        String negotiatedProtocol = getNegotiatedProtocol();
        ConnectionFactory connectionFactory = connector.getConnectionFactory(negotiatedProtocol);
        if (connectionFactory == null)
            connectionFactory = connector.getDefaultConnectionFactory();
        if (connectionFactory == null)
            throw new RuntimeIOException("No configured connection factory can handle protocol '" + negotiatedProtocol + "'");

        QuicStreamEndPoint endPoint = new QuicStreamEndPoint(getScheduler(), this, streamId);
        Connection connection = connectionFactory.newConnection(connector, endPoint);
        endPoint.setConnection(connection);
        endPoint.onOpen();
        connection.onOpen();
        return endPoint;
    }
}
