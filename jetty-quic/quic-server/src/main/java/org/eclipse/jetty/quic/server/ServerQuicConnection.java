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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.quic.common.QuicConnection;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.quiche.QuicheConfig;
import org.eclipse.jetty.quic.quiche.QuicheConnection;
import org.eclipse.jetty.quic.quiche.ffi.LibQuiche;
import org.eclipse.jetty.quic.server.internal.SimpleTokenMinter;
import org.eclipse.jetty.quic.server.internal.SimpleTokenValidator;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The server specific implementation of {@link QuicConnection}.</p>
 */
public class ServerQuicConnection extends QuicConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(ServerQuicConnection.class);

    private final QuicheConfig quicheConfig;
    private final Connector connector;

    protected ServerQuicConnection(Connector connector, EndPoint endPoint, QuicheConfig quicheConfig)
    {
        super(connector.getExecutor(), connector.getScheduler(), connector.getByteBufferPool(), endPoint);
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
    protected QuicSession createSession(SocketAddress remoteAddress, ByteBuffer cipherBuffer) throws IOException
    {
        ByteBufferPool byteBufferPool = getByteBufferPool();
        // TODO make the token validator configurable
        QuicheConnection quicheConnection = QuicheConnection.tryAccept(quicheConfig, new SimpleTokenValidator((InetSocketAddress)remoteAddress), cipherBuffer, remoteAddress);
        if (quicheConnection == null)
        {
            // TODO make the buffer size configurable
            ByteBuffer negotiationBuffer = byteBufferPool.acquire(LibQuiche.QUICHE_MIN_CLIENT_INITIAL_LEN, true);
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
}
