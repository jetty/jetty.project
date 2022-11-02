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
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.server.internal.SimpleTokenMinter;
import org.eclipse.jetty.quic.server.internal.SimpleTokenValidator;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The server specific implementation of {@link QuicConnection}.</p>
 */
public class ServerQuicConnection extends QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicConnection.class);

    private final QuicServerConnector connector;
    private final SessionTimeouts sessionTimeouts;

    protected ServerQuicConnection(QuicServerConnector connector, EndPoint endPoint)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.sessionTimeouts = new SessionTimeouts(connector.getScheduler());
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    protected QuicSession createSession(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        ByteBufferPool byteBufferPool = getByteBufferPool();
        // TODO make the token validator configurable
        QuicheConnection quicheConnection = QuicheConnection.tryAccept(connector.newQuicheConfig(), new SimpleTokenValidator((InetSocketAddress)remoteAddress), cipherBuffer, getEndPoint().getLocalAddress(), remoteAddress);
        if (quicheConnection == null)
        {
            ByteBuffer negotiationBuffer = byteBufferPool.acquire(getOutputBufferSize(), true);
            int pos = BufferUtil.flipToFill(negotiationBuffer);
            // TODO make the token minter configurable
            if (!QuicheConnection.negotiate(new SimpleTokenMinter((InetSocketAddress)remoteAddress), cipherBuffer, negotiationBuffer))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("QUIC connection negotiation failed, dropping packet");
                byteBufferPool.release(negotiationBuffer);
                return null;
            }
            BufferUtil.flipToFlush(negotiationBuffer, pos);

            write(Callback.from(() -> byteBufferPool.release(negotiationBuffer)), remoteAddress, negotiationBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("QUIC connection negotiation packet sent");
            return null;
        }
        else
        {
            QuicSession session = new ServerQuicSession(getExecutor(), getScheduler(), byteBufferPool, quicheConnection, this, remoteAddress, connector);
            // Send the response packet(s) that tryAccept() generated.
            session.flush();
            return session;
        }
    }

    public void schedule(ServerQuicSession session)
    {
        sessionTimeouts.schedule(session);
    }

    @Override
    public boolean onIdleExpired()
    {
        // The current server architecture only has one listening
        // DatagramChannelEndPoint, so we ignore idle timeouts.
        return false;
    }

    @Override
    public void outwardClose(QuicSession session, Throwable failure)
    {
        super.outwardClose(session, failure);
        // Do nothing else, as the current architecture only has one
        // listening DatagramChannelEndPoint, so it must not be closed.
    }

    private class SessionTimeouts extends CyclicTimeouts<ServerQuicSession>
    {
        private SessionTimeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<ServerQuicSession> iterator()
        {
            return getQuicSessions().stream()
                .map(ServerQuicSession.class::cast)
                .iterator();
        }

        @Override
        protected boolean onExpired(ServerQuicSession session)
        {
            session.onIdleTimeout();
            return false;
        }
    }
}
