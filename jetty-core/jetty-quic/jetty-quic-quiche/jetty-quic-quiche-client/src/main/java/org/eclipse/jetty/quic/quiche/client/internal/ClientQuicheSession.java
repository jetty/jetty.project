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

package org.eclipse.jetty.quic.quiche.client.internal;

import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.common.StreamId;
import org.eclipse.jetty.quic.quiche.Quiche;
import org.eclipse.jetty.quic.quiche.QuicheSession;
import org.eclipse.jetty.quic.quiche.client.QuicheClientQuicConfiguration;

/**
 * <p>The client specific implementation of {@link QuicheSession}.</p>
 */
public class ClientQuicheSession extends QuicheSession
{
    private final AtomicLong biStreamIds = new AtomicLong();
    private final AtomicLong uniStreamIds = new AtomicLong();

    public ClientQuicheSession(ClientConnector connector, QuicheClientQuicConfiguration configuration, Quiche quiche, ClientQuicheConnection connection, SocketAddress localAddress, SocketAddress remoteAddress, Session.Listener listener)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), configuration, quiche, connection, localAddress, remoteAddress, listener);
    }

    @Override
    public long newStreamId(boolean bidirectional)
    {
        AtomicLong streamIds = bidirectional ? biStreamIds : uniStreamIds;
        return StreamId.newStreamId(streamIds.getAndIncrement(), bidirectional, true);
    }

    @Override
    protected ClientQuicheConnection getConnection()
    {
        return (ClientQuicheConnection)super.getConnection();
    }

    public ClientConnectionFactory getClientConnectionFactory()
    {
        return getConnection().getClientConnectionFactory();
    }
}
