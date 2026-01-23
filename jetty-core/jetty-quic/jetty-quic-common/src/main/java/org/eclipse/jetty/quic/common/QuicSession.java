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
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.HandshakeDoneFrame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.frames.FrameStream;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.QuicFlusher;
import org.eclipse.jetty.quic.common.internal.packets.PacketsParser;
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.ZeroRTTPacket;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicSession extends AbstractSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final Map<EncryptionLevel, FrameStream> cryptoStreams = new HashMap<>();
    private final Map<Long, FrameStream> streamStreams = new HashMap<>();
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final QuicConnection connection;
    private final PacketNumbers packetNumbers;
    private final TLSEngine tlsEngine;
    private final EndPoint endPoint;
    private final PacketsParser parser;
    private final QuicFlusher flusher;
    private Packet.Listener packetListener;
    private byte[] dstConnectionId;
    private byte[] srcConnectionId;
    private long idleTimeout;
    private SocketAddress remoteSocketAddress;

    protected QuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicConfiguration quicConfiguration, QuicConnection connection, PacketNumbers packetNumbers, TLSEngine tlsEngine, Session.Listener listener, EndPoint endPoint)
    {
        super(executor, quicConfiguration, listener);
        this.scheduler = scheduler;
        this.byteBufferPool = byteBufferPool;
        this.connection = connection;
        this.packetNumbers = packetNumbers;
        this.tlsEngine = tlsEngine;
        this.endPoint = endPoint;
        this.parser = new PacketsParser(tlsEngine.getPacketProtector(), packetNumbers, new FramesParser());
        this.flusher = new QuicFlusher(this);
        this.packetListener = new PacketProcessor();
        this.dstConnectionId = BufferUtil.EMPTY_BYTES;
        this.srcConnectionId = tlsEngine.newRandomBytes(8);
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public QuicConnection getQuicConnection()
    {
        return connection;
    }

    public PacketNumbers getPacketNumbers()
    {
        return packetNumbers;
    }

    public TLSEngine getTLSEngine()
    {
        return tlsEngine;
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
        parser.setDestinationConnectionId(dstConnectionId);
    }

    public byte[] getSourceConnectionId()
    {
        return srcConnectionId;
    }

    public String getNegotiatedApplicationProtocol()
    {
        return getTLSEngine().getNegotiatedApplicationProtocol();
    }

    @Override
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public boolean onIdleTimeout(TimeoutException timeout)
    {
        // TODO
        return false;
    }

    public Packet newPacket(List<Frame> frames)
    {
        QuicVersion quicVersion = getQuicConfiguration().getQuicVersion();
        EncryptionLevel encryptionLevel = getTLSEngine().getPacketProtector().getEncryptionLevel();
        Packet packet = switch (encryptionLevel)
        {
            case EncryptionLevel.INITIAL -> newInitialPacket(frames);
            case EncryptionLevel.HANDSHAKE ->
                new HandshakePacket(quicVersion, getDestinationConnectionId(), getSourceConnectionId(), packetNumbers.nextPacketNumber(encryptionLevel), frames);
            // TODO
            default -> throw new IllegalStateException();
        };
        if (LOG.isDebugEnabled())
            LOG.debug("produced {} on {}", packet, this);
        return packet;
    }

    protected abstract InitialPacket newInitialPacket(List<Frame> frames);

    /// Sends a CRYPTO frame on this session.
    ///
    /// @param frame the frame to send
    /// @param callback the [Callback] that gets notified when the frame has been sent
    protected void crypto(CryptoFrame frame, Callback callback)
    {
        if (flusher.offer(this, List.of(frame), callback))
            flusher.iterate();
    }

    protected void handshakeDone(HandshakeDoneFrame frame, Callback callback)
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
        return getEndPoint().getLocalSocketAddress();
    }

    @Override
    public SocketAddress getRemoteSocketAddress()
    {
        return remoteSocketAddress;
    }

    public void setRemoteSocketAddress(SocketAddress socketAddress)
    {
        this.remoteSocketAddress = socketAddress;
    }

    @Override
    public long getLocalBidirectionalMaxStreams()
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

    public void process(SocketAddress remoteSocketAddress, RetainableByteBuffer buffer) throws Exception
    {
        setRemoteSocketAddress(remoteSocketAddress);
        while (buffer.hasRemaining())
        {
            Packet packet = parser.parse(buffer);

            if (LOG.isDebugEnabled())
                LOG.debug("parsed {} on {}", packet, this);

            if (packet == null)
            {
                // TODO: is this case possible in practice?
                // TODO: consume the buffer?
                return;
            }

            if (packet instanceof InitialPacket || Arrays.equals(srcConnectionId, packet.destinationConnectionId()))
            {
                notifyIncomingPacket(packet);
                return;
            }

            // RFC-9000[7.2]: the packet must be discarded
            // if destination connection ID does not match.
            if (LOG.isDebugEnabled())
                LOG.debug("packet does not match connection id on {}", this);
            // TODO: consume the buffer?
            return;
        }
    }

    protected void processPacket(Packet packet)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", packet, this);

        switch (packet)
        {
            case InitialPacket initialPacket ->
            {
                EncryptionLevel encryptionLevel = getTLSEngine().getPacketProtector().getEncryptionLevel();
                if (encryptionLevel != EncryptionLevel.INITIAL)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarded {} at encryption level {} on {} ", packet, encryptionLevel, this);
                    return;
                }
                setDestinationConnectionId(initialPacket.sourceConnectionId());
                processFrames(initialPacket.frames());
                ack(initialPacket);
            }
            case HandshakePacket handshakePacket ->
            {
                EncryptionLevel encryptionLevel = getTLSEngine().getPacketProtector().getEncryptionLevel();
                if (encryptionLevel == EncryptionLevel.INITIAL)
                    getTLSEngine().getPacketProtector().updateEncryptionLevel(EncryptionLevel.HANDSHAKE);
                encryptionLevel = getTLSEngine().getPacketProtector().getEncryptionLevel();
                if (encryptionLevel != EncryptionLevel.HANDSHAKE)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("discarded {} at encryption level {} on {} ", packet, encryptionLevel, this);
                    return;
                }
                processFrames(handshakePacket.frames());
                ack(handshakePacket);
            }
            case ZeroRTTPacket zeroRTTPacket ->
            {
                // TODO:
                processFrames(zeroRTTPacket.frames());
            }
            case OneRTTPacket oneRTTPacket ->
            {
                // TODO: handle here keyPhase shift?
                processFrames(oneRTTPacket.frames());
                ack(oneRTTPacket);
            }
            // RetryPacket and VersionNegotiationPacket only handled by clients.
            default -> throw new UnsupportedOperationException();
        }
    }

    protected void processFrames(List<Frame> frames)
    {
        for (Frame frame : frames)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("processing {} on {}", frame, this);

            switch (frame)
            {
                case AckFrame ackFrame ->
                {
                    // TODO: notify reliability data structure.
                }
                case CryptoFrame cryptoFrame ->
                {
                    EncryptionLevel encryptionLevel = getTLSEngine().getPacketProtector().getEncryptionLevel();
                    cryptoStreams.computeIfAbsent(encryptionLevel, _ -> new FrameStream(this::processCryptoFrame)).offer(cryptoFrame);
                }
                case StreamFrame streamFrame ->
                {
                    long streamId = streamFrame.streamId();
                    streamStreams.computeIfAbsent(streamId, _ -> new FrameStream(this::processStreamFrame)).offer(streamFrame);
                }
                default ->
                {
                    // TODO: notify Session.Listener
                }
            }
        }
    }

    private void processCryptoFrame(Frame frame)
    {
        try
        {
            CryptoFrame cryptoFrame = (CryptoFrame)frame;
            RetainableByteBuffer data = cryptoFrame.data();
            while (data.hasRemaining())
            {
                Message message = getTLSEngine().getMessagesParser().parse(data);
                if (LOG.isDebugEnabled())
                    LOG.debug("parsed {} on {}", message, this);
                if (message == null)
                    return;
                processMessage(message);
            }
        }
        catch (Throwable x)
        {
            fail(x);
        }
    }

    protected abstract void processMessage(Message message);

    private void processStreamFrame(Frame frame)
    {
        StreamFrame streamFrame = (StreamFrame)frame;
        if (streamFrame.isEndStream())
            streamStreams.remove(streamFrame.streamId());

        // TODO: computeIfAbsent() the Stream object.
        //  Then offer frame data to the stream object.
        //  Then notify the Stream.Listener.
    }

    private void ack(Packet.WithPacketNumber packet)
    {
        // TODO: notify reliability data structure?
        //  Or leave that only for sent packets and received acks?
        AckFrame ackFrame = new AckFrame(packet.packetNumber(), 0, 0, List.of());
        if (flusher.offer(this, List.of(ackFrame), Callback.NOOP))
            flusher.iterate();
    }

    public Packet.Listener getPacketListener()
    {
        return packetListener;
    }

    public void setPacketListener(Packet.Listener listener)
    {
        packetListener = listener;
    }

    protected void sendTLSMessages(List<Message> messages, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sending TLS messages {} on {}", messages, this);

        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(getByteBufferPool(), getQuicConfiguration().isUseOutputDirectByteBuffers(), -1, 0, 0);
        try
        {
            for (Message message : messages)
            {
                getTLSEngine().getMessagesGenerator().generate(accumulator, message);
            }
            // TODO: cannot assume offset is 0 here.
            CryptoFrame cryptoFrame = new CryptoFrame(0, accumulator);
            crypto(cryptoFrame, callback);
        }
        catch (Throwable x)
        {
            accumulator.release();
            callback.failed(x);
        }
    }

    public void notifyIncomingPacket(Packet packet)
    {
        try
        {
            packetListener.onIncomingPacket(this, packet);
        }
        catch (Throwable x)
        {
            LOG.atInfo().setCause(x).log("failure while notifying listener {}", packetListener);
        }
    }

    public void notifyOutgoingPacket(Packet packet)
    {
        try
        {
            packetListener.onOutgoingPacket(this, packet);
        }
        catch (Throwable x)
        {
            LOG.atInfo().setCause(x).log("failure while notifying listener {}", packetListener);
        }
    }

    public void fail(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.atDebug().setCause(x).log("failure on {}", this);
        // TODO: initiate inward close? or outward?
    }

    @Override
    public String toString()
    {
        return "%s[dcid=%s]".formatted(super.toString(), StringUtil.toHexString(getDestinationConnectionId()));
    }

    private class PacketProcessor implements Packet.Listener
    {
        @Override
        public void onIncomingPacket(Session session, Packet packet)
        {
            processPacket(packet);
        }
    }
}
