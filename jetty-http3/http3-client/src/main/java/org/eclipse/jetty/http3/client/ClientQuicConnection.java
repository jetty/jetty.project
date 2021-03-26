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

package org.eclipse.jetty.http3.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicSession;
import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientQuicConnection extends QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicConnection.class);

    private final Map<InetSocketAddress, QuicSession> pendingSessions = new ConcurrentHashMap<>();
    private final QuicheConfig quicheConfig;
    private final Map<String, Object> context;

    public ClientQuicConnection(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, EndPoint endp, QuicheConfig quicheConfig, Map<String, Object> context)
    {
        super(executor, scheduler, byteBufferPool, endp);
        this.quicheConfig = quicheConfig;
        this.context = context;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();

        try
        {
            InetSocketAddress remoteAddress = (InetSocketAddress)context.get(ClientConnector.REMOTE_SOCKET_ADDRESS_CONTEXT_KEY);
            QuicheConnection quicheConnection = QuicheConnection.connect(quicheConfig, remoteAddress);
            QuicSession session = new ClientQuicSession(getExecutor(), getScheduler(), getByteBufferPool(), quicheConnection, this, remoteAddress, context);
            pendingSessions.put(remoteAddress, session);
            session.flush(); // send the response packet(s) that connect generated.
            if (LOG.isDebugEnabled())
                LOG.debug("created connecting QUIC session {}", session);
        }
        catch (IOException e)
        {
            throw new RuntimeIOException("Error trying to open connection", e);
        }

        fillInterested();
    }

    @Override
    protected void closeSession(QuicheConnectionId quicheConnectionId, QuicSession session, Throwable x)
    {
        super.closeSession(quicheConnectionId, session, x);

        InetSocketAddress remoteAddress = session.getRemoteAddress();
        if (pendingSessions.remove(remoteAddress) != null)
        {
            Promise<?> promise = (Promise<?>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
            if (promise != null)
                promise.failed(x);
        }
    }

    @Override
    protected QuicSession createSession(InetSocketAddress remoteAddress, ByteBuffer cipherBuffer)
    {
        return pendingSessions.get(remoteAddress);
    }

    @Override
    protected boolean promoteSession(QuicheConnectionId quicheConnectionId, QuicSession session)
    {
        InetSocketAddress remoteAddress = session.getRemoteAddress();
        if (pendingSessions.containsKey(remoteAddress) && session.isConnectionEstablished())
        {
            pendingSessions.remove(remoteAddress);
            session.setConnectionId(quicheConnectionId);
            session.createStream(0);
            return true;
        }
        return false;
    }
}
