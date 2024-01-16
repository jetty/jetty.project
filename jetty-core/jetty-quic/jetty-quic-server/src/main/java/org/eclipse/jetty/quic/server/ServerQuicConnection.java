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

package org.eclipse.jetty.quic.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
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

    public ServerQuicConnection(QuicServerConnector connector, EndPoint endPoint)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
        this.connector = connector;
        this.sessionTimeouts = new SessionTimeouts(connector.getScheduler());
    }

    public QuicServerConnector getQuicServerConnector()
    {
        return connector;
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
        ByteBufferPool bufferPool = getByteBufferPool();
        // TODO make the token validator configurable
        QuicheConnection quicheConnection = QuicheConnection.tryAccept(connector.newQuicheConfig(), new SimpleTokenValidator((InetSocketAddress)remoteAddress), cipherBuffer, getEndPoint().getLocalAddress(), remoteAddress);
        if (quicheConnection == null)
        {
            RetainableByteBuffer negotiationBuffer = bufferPool.acquire(getOutputBufferSize(), true);
            ByteBuffer byteBuffer = negotiationBuffer.getByteBuffer();
            int pos = BufferUtil.flipToFill(byteBuffer);
            // TODO make the token minter configurable
            if (!QuicheConnection.negotiate(new SimpleTokenMinter((InetSocketAddress)remoteAddress), cipherBuffer, byteBuffer))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("QUIC connection negotiation failed, dropping packet");
                negotiationBuffer.release();
                return null;
            }
            BufferUtil.flipToFlush(byteBuffer, pos);

            write(Callback.from(negotiationBuffer::release), remoteAddress, byteBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("QUIC connection negotiation packet sent");
            return null;
        }
        else
        {
            ServerQuicSession session = newQuicSession(remoteAddress, quicheConnection);
            // Send the response packet(s) that tryAccept() generated.
            session.flush();
            return session;
        }
    }

    protected ServerQuicSession newQuicSession(SocketAddress remoteAddress, QuicheConnection quicheConnection)
    {
        return new ServerQuicSession(getExecutor(), getScheduler(), getByteBufferPool(), quicheConnection, this, remoteAddress, getQuicServerConnector());
    }

    public void schedule(ServerQuicSession session)
    {
        sessionTimeouts.schedule(session);
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeoutException)
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
            // The implementation of the Iterator returned above does not support
            // removal, but the session will be removed by session.onIdleTimeout().
            return false;
        }
    }
}
