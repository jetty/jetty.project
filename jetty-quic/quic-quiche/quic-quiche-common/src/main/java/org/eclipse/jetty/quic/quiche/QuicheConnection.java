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

package org.eclipse.jetty.quic.quiche;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicheConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicheConnection.class);
    static final QuicheBinding QUICHE_BINDING;

    static
    {
        if (LOG.isDebugEnabled())
        {
            List<QuicheBinding> bindings = TypeUtil.serviceStream(ServiceLoader.load(QuicheBinding.class))
                .sorted(Comparator.comparingInt(QuicheBinding::priority))
                .collect(Collectors.toList());
            LOG.debug("found quiche binding implementations: {}", bindings);
        }
        QUICHE_BINDING = TypeUtil.serviceStream(ServiceLoader.load(QuicheBinding.class))
            .filter(QuicheBinding::isUsable)
            .min(Comparator.comparingInt(QuicheBinding::priority))
            .orElseThrow(() -> new IllegalStateException("no quiche binding implementation found"));
        if (LOG.isDebugEnabled())
            LOG.debug("using quiche binding implementation: {}", QUICHE_BINDING.getClass().getName());
    }

    public static QuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress local, InetSocketAddress peer) throws IOException
    {
        return connect(quicheConfig, local, peer, Quiche.QUICHE_MAX_CONN_ID_LEN);
    }

    public static QuicheConnection connect(QuicheConfig quicheConfig, InetSocketAddress local, InetSocketAddress peer, int connectionIdLength) throws IOException
    {
        return QUICHE_BINDING.connect(quicheConfig, local, peer, connectionIdLength);
    }

    /**
     * Fully consumes the {@code packetRead} buffer.
     * @return true if a negotiation packet was written to the {@code packetToSend} buffer, false if negotiation failed
     * and the {@code packetRead} buffer can be dropped.
     */
    public static boolean negotiate(TokenMinter tokenMinter, ByteBuffer packetRead, ByteBuffer packetToSend) throws IOException
    {
        return QUICHE_BINDING.negotiate(tokenMinter, packetRead, packetToSend);
    }

    /**
     * Fully consumes the {@code packetRead} buffer if the connection was accepted.
     * @return an established connection if accept succeeded, null if accept failed and negotiation should be tried.
     */
    public static QuicheConnection tryAccept(QuicheConfig quicheConfig, TokenValidator tokenValidator, ByteBuffer packetRead, SocketAddress local, SocketAddress peer) throws IOException
    {
        return QUICHE_BINDING.tryAccept(quicheConfig, tokenValidator, packetRead, local, peer);
    }

    public final List<Long> readableStreamIds()
    {
        return iterableStreamIds(false);
    }

    public final List<Long> writableStreamIds()
    {
        return iterableStreamIds(true);
    }

    protected abstract List<Long> iterableStreamIds(boolean write);

    /**
     * Read the buffer of cipher text coming from the network.
     * @param buffer the buffer to read.
     * @param local the local address on which the buffer was received.
     * @param peer the address of the peer from which the buffer was received.
     * @return how many bytes were consumed.
     * @throws IOException
     */
    public abstract int feedCipherBytes(ByteBuffer buffer, SocketAddress local, SocketAddress peer) throws IOException;

    /**
     * Fill the given buffer with cipher text to be sent.
     * @param buffer the buffer to fill.
     * @return how many bytes were added to the buffer.
     * @throws IOException
     */
    public abstract int drainCipherBytes(ByteBuffer buffer) throws IOException;

    public abstract boolean isConnectionClosed();

    public abstract boolean isConnectionEstablished();

    public abstract long nextTimeout();

    public abstract void onTimeout();

    public abstract String getNegotiatedProtocol();

    public abstract boolean close(long error, String reason);

    public abstract void dispose();

    public abstract boolean isDraining();

    public abstract int maxLocalStreams();

    public abstract long windowCapacity();

    public abstract long windowCapacity(long streamId) throws IOException;

    public abstract void shutdownStream(long streamId, boolean writeSide, long error) throws IOException;

    public final void feedFinForStream(long streamId) throws IOException
    {
        feedClearBytesForStream(streamId, BufferUtil.EMPTY_BUFFER, true);
    }

    public final int feedClearBytesForStream(long streamId, ByteBuffer buffer) throws IOException
    {
        return feedClearBytesForStream(streamId, buffer, false);
    }

    public abstract int feedClearBytesForStream(long streamId, ByteBuffer buffer, boolean last) throws IOException;

    public abstract int drainClearBytesForStream(long streamId, ByteBuffer buffer) throws IOException;

    public abstract boolean isStreamFinished(long streamId);

    public abstract CloseInfo getRemoteCloseInfo();

    public static class CloseInfo
    {
        private final long error;
        private final String reason;

        public CloseInfo(long error, String reason)
        {
            this.error = error;
            this.reason = reason;
        }

        public long error()
        {
            return error;
        }

        public String reason()
        {
            return reason;
        }
    }

    public interface TokenMinter
    {
        int MAX_TOKEN_LENGTH = 48;
        byte[] mint(byte[] dcid, int len);
    }

    public interface TokenValidator
    {
        byte[] validate(byte[] token, int len);
    }

    public static class TokenValidationException extends IOException
    {
        public TokenValidationException(String msg)
        {
            super(msg);
        }
    }
}
