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

package org.eclipse.jetty.quic.server;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.quic.common.ProtocolSession;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>The server specific implementation of {@link QuicSession}.</p>
 * <p>When asked to create a QUIC stream, it creates a {@link QuicStreamEndPoint}
 * with an associated {@link Connection} created from the {@link ConnectionFactory},
 * retrieved from the server {@link Connector}, correspondent to the protocol
 * negotiated with the client (or the default protocol).</p>
 */
public class ServerQuicSession extends QuicSession implements CyclicTimeouts.Expirable
{
    private final Connector connector;
    private long expireNanoTime;

    protected ServerQuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicheConnection quicheConnection, QuicConnection connection, SocketAddress remoteAddress, Connector connector)
    {
        super(executor, scheduler, byteBufferPool, quicheConnection, connection, remoteAddress);
        this.connector = connector;
    }

    @Override
    public ServerQuicConnection getQuicConnection()
    {
        return (ServerQuicConnection)super.getQuicConnection();
    }

    @Override
    protected ProtocolSession createProtocolSession()
    {
        ConnectionFactory connectionFactory = findConnectionFactory(getNegotiatedProtocol());
        if (connectionFactory instanceof ProtocolSession.Factory)
            return ((ProtocolSession.Factory)connectionFactory).newProtocolSession(this, Map.of());
        return new ServerProtocolSession(this);
    }

    @Override
    public Connection newConnection(QuicStreamEndPoint endPoint)
    {
        ConnectionFactory connectionFactory = findConnectionFactory(getNegotiatedProtocol());
        return newConnection(connectionFactory, endPoint);
    }

    private Connection newConnection(ConnectionFactory factory, QuicStreamEndPoint endPoint)
    {
        return factory.newConnection(connector, endPoint);
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

    @Override
    public long getExpireNanoTime()
    {
        return expireNanoTime;
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        notIdle();
        getQuicConnection().schedule(this);
    }

    @Override
    public Runnable process(SocketAddress remoteAddress, ByteBuffer cipherBufferIn) throws IOException
    {
        notIdle();
        return super.process(remoteAddress, cipherBufferIn);
    }

    @Override
    public void flush()
    {
        notIdle();
        super.flush();
    }

    private void notIdle()
    {
        long idleTimeout = getIdleTimeout();
        if (idleTimeout > 0)
            expireNanoTime = NanoTime.now() + TimeUnit.MILLISECONDS.toNanos(idleTimeout);
    }
}
