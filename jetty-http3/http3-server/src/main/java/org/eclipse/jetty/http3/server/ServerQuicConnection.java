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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.common.QuicConnection;
import org.eclipse.jetty.http3.common.QuicDatagramEndPoint;
import org.eclipse.jetty.http3.common.QuicSession;
import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.http3.quiche.QuicheConnection;
import org.eclipse.jetty.http3.quiche.QuicheConnectionId;
import org.eclipse.jetty.http3.quiche.ffi.LibQuiche;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServerQuicConnection extends QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicConnection.class);

    private final QuicheConfig quicheConfig;
    private final Connector connector;

    protected ServerQuicConnection(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, EndPoint endp, QuicheConfig quicheConfig, Connector connector)
    {
        super(executor, scheduler, byteBufferPool, endp);
        this.quicheConfig = quicheConfig;
        this.connector = connector;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    protected QuicSession createSession(InetSocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        ByteBufferPool byteBufferPool = getByteBufferPool();
        // TODO make the token validator configurable
        QuicheConnection quicheConnection = QuicheConnection.tryAccept(quicheConfig, new SimpleTokenValidator(remoteAddress), cipherBuffer);
        if (quicheConnection == null)
        {
            // TODO make the buffer size configurable
            ByteBuffer negotiationBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
            int pos = BufferUtil.flipToFill(negotiationBuffer);
            // TODO make the token minter configurable
            if (!QuicheConnection.negotiate(new SimpleTokenMinter(remoteAddress), cipherBuffer, negotiationBuffer))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("QUIC connection negotiation failed, dropping packet");
                byteBufferPool.release(negotiationBuffer);
                return null;
            }
            BufferUtil.flipToFlush(negotiationBuffer, pos);

            QuicDatagramEndPoint.INET_ADDRESS_ARGUMENT.push(remoteAddress);
            getEndPoint().write(Callback.from(() -> byteBufferPool.release(negotiationBuffer)), negotiationBuffer);
            if (LOG.isDebugEnabled())
                LOG.debug("QUIC connection negotiation packet sent");
            return null;
        }
        else
        {
            QuicSession session = new ServerQuicSession(getExecutor(), getScheduler(), byteBufferPool, quicheConnection, this, remoteAddress, connector);
            session.flush(); // send the response packet(s) that tryAccept generated.
            return session;
        }
    }

    @Override
    protected boolean promoteSession(QuicheConnectionId quicheConnectionId, QuicSession session)
    {
        session.setConnectionId(quicheConnectionId);
        return true;
    }
}
