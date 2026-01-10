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

package org.eclipse.jetty.quic.common;

import java.net.SocketAddress;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.QuicFlusher;
import org.eclipse.jetty.quic.common.internal.packets.PacketsParser;
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.tls.QuicTLS;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicSession extends AbstractSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final List<Packet.Listener> packetListeners = new ArrayList<>();
    private final QuicFlusher flusher = new QuicFlusher(this);
    private final ByteBufferPool byteBufferPool;
    private final PacketsParser parser;
    private final PacketNumbers packetNumbers;
    private final QuicTLS quicTLS;
    private final EndPoint endPoint;
    private byte[] dstConnectionId;
    private byte[] srcConnectionId;

    protected QuicSession(Executor executor, ByteBufferPool byteBufferPool, QuicConfiguration quicConfiguration, PacketNumbers packetNumbers, QuicTLS quicTLS, Session.Listener listener, EndPoint endPoint)
    {
        super(executor, quicConfiguration, listener);
        this.byteBufferPool = byteBufferPool;
        this.packetNumbers = packetNumbers;
        this.quicTLS = quicTLS;
        this.endPoint = endPoint;
        this.parser = new PacketsParser(quicTLS, packetNumbers, new FramesParser());
        this.srcConnectionId = quicTLS.newRandomBytes(8);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public PacketNumbers getPacketNumbers()
    {
        return packetNumbers;
    }

    public QuicTLS getQuicTLS()
    {
        return quicTLS;
    }

    public EndPoint getEndPoint()
    {
        return endPoint;
    }

    public byte[] getDestinationConnectionId()
    {
        return dstConnectionId;
    }

    protected void setDestinationConnectionId(byte[] dstConnectionId)
    {
        this.dstConnectionId = dstConnectionId;
    }

    public byte[] getSourceConnectionId()
    {
        return srcConnectionId;
    }

    public Packet newPacket(List<Frame> frames)
    {
        QuicVersion quicVersion = getQuicConfiguration().getQuicVersion();
        EncryptionLevel encryptionLevel = quicTLS.getEncryptionLevel();
        return switch (encryptionLevel)
        {
            case EncryptionLevel.INITIAL ->
            {
                byte[] token = quicTLS.newRandomBytes(32);
                yield new InitialPacket(quicVersion, getDestinationConnectionId(), getSourceConnectionId(), token, packetNumbers.nextPacketNumber(encryptionLevel), frames);
            }
            case EncryptionLevel.HANDSHAKE ->
                new HandshakePacket(quicVersion, getDestinationConnectionId(), getSourceConnectionId(), packetNumbers.nextPacketNumber(encryptionLevel), frames);
            // TODO
            default -> throw new IllegalStateException();
        };
    }

    /// Sends a CRYPTO frame on this session.
    ///
    /// @param frame the frame to send
    /// @param callback the [Callback] that gets notified when the frame has been sent
    public void crypto(CryptoFrame frame, Callback callback)
    {
        if (flusher.offer(this, List.of(frame), callback))
            flusher.iterate();
    }

    @Override
    public String getId()
    {
        return "";
    }

    @Override
    public long newStreamId(boolean bidirectional)
    {
        return 0;
    }

    @Override
    public Stream newStream(long streamId, Stream.Listener listener)
    {
        return null;
    }

    @Override
    public Stream getStream(long streamId)
    {
        return null;
    }

    @Override
    public Collection<Stream> getStreams()
    {
        return List.of();
    }

    @Override
    public void maxStreams(MaxStreamsFrame frame, Promise.Invocable<Session> promise)
    {

    }

    @Override
    public void ping(Promise.Invocable<Session> promise)
    {

    }

    @Override
    public void maxData(MaxDataFrame frame, Promise.Invocable<Session> promise)
    {

    }

    @Override
    public void disconnect(ConnectionCloseFrame frame, Throwable failure, Promise.Invocable<Session> promise)
    {

    }

    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return null;
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return null;
    }

    @Override
    public long getLocalBidirectionalMaxStreams()
    {
        return 0;
    }

    @Override
    public long getIdleTimeout()
    {
        return 0;
    }

    @Override
    public X509Certificate[] getPeerCertificates()
    {
        return new X509Certificate[0];
    }

    @Override
    public void offerTask(Runnable task, boolean dispatch)
    {

    }

    public void process(RetainableByteBuffer buffer) throws Exception
    {
        Packet packet = parser.parse(buffer);

        // RFC 9000, 7.2: the packet must be discarded
        // if destination connection ID does not match.
        if (!Arrays.equals(srcConnectionId, packet.destinationConnectionId()))
            return;

        notifyIncomingPacket(packet);

        process(packet);
    }

    private void process(Packet packet)
    {
        // TODO: whatever packet contains a CRYPTO, we must feed a FrameStream.
        //  It's then the FrameStream that notifies of TLS bytes.
        //  There is a crypto FrameStream per EncryptionLevel.
    }

    public void addPacketListener(Packet.Listener listener)
    {
        packetListeners.add(listener);
    }

    public void notifyIncomingPacket(Packet packet)
    {
        for (Packet.Listener listener : packetListeners)
        {
            try
            {
                listener.onIncomingPacket(this, packet);
            }
            catch (Throwable x)
            {
                LOG.atInfo().setCause(x).log("failure while notifying listener {}", listener);
            }
        }
    }

    public void notifyOutgoingPacket(Packet packet)
    {
        for (Packet.Listener listener : packetListeners)
        {
            try
            {
                listener.onOutgoingPacket(this, packet);
            }
            catch (Throwable x)
            {
                LOG.atInfo().setCause(x).log("failure while notifying listener {}", listener);
            }
        }
    }
}
