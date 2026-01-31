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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

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
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.PingFrame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.frames.FrameStream;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.QuicFlusher;
import org.eclipse.jetty.quic.common.internal.packets.PacketsParser;
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.quic.common.packets.ZeroRTTPacket;
import org.eclipse.jetty.quic.common.tls.TLSEngine;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicSession extends AbstractSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final AutoLock lock = new AutoLock();
    private final Map<EncryptionLevel, FrameStream> cryptoStreams = new HashMap<>();
    private final Map<Long, QuicStream> streams = new ConcurrentHashMap<>();
    private final AtomicLong biRemoteStreamCount = new AtomicLong();
    private final AtomicLong biRemoteStreamMaxCount = new AtomicLong();
    private final AtomicLong uniRemoteStreamCount = new AtomicLong();
    private final AtomicLong uniRemoteStreamMaxCount = new AtomicLong();
    private final AtomicLong sendData = new AtomicLong();
    private final AtomicLong sendMaxData = new AtomicLong();
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
    private TransportParameters transportParameters;
    private boolean writeStalled;

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

    public abstract int getUDPPayloadLength();

    /// Returns an estimate (by excess) of the packet header length.
    ///
    /// @param encryptionLevel the encryption level of the packet
    public int estimatePacketHeaderLength(EncryptionLevel encryptionLevel)
    {
        // Use the UDP payload length as the length of the packet payload.
        long length = getUDPPayloadLength();
        return switch (encryptionLevel)
        {
            // Form, version, dcid, scid, no token, length, packet number.
            case INITIAL ->
                1 + 4 + 1 + getDestinationConnectionId().length + 1 + getSourceConnectionId().length + 1 + VarLenInt.length(length) + 4;
            // Form, version, dcid, scid, length, packet number.
            case HANDSHAKE, ZERO_RTT ->
                1 + 4 + 1 + getDestinationConnectionId().length + 1 + getSourceConnectionId().length + VarLenInt.length(length) + 4;
            // Form, dcid, packet number.
            case ONE_RTT -> 1 + getDestinationConnectionId().length + 4;
        };
    }

    public Packet newPacket(EncryptionLevel encryptionLevel, List<Frame> frames)
    {
        QuicVersion quicVersion = getQuicConfiguration().getQuicVersion();
        Packet packet = switch (encryptionLevel)
        {
            case EncryptionLevel.INITIAL -> newInitialPacket(frames);
            case EncryptionLevel.HANDSHAKE ->
                new HandshakePacket(quicVersion, getDestinationConnectionId(), getSourceConnectionId(), packetNumbers.nextPacketNumber(encryptionLevel), frames);
            case ONE_RTT ->
                new OneRTTPacket(packetNumbers.nextPacketNumber(encryptionLevel), getDestinationConnectionId(), false, false, frames);
            case ZERO_RTT -> throw new IllegalStateException();
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
    protected void crypto(EncryptionLevel encryptionLevel, CryptoFrame frame, Callback callback)
    {
        if (flusher.offer(encryptionLevel, List.of(frame), callback))
            flusher.iterate();
    }

    protected void resetCrypto()
    {
        flusher.resetCrypto();
    }

    protected void frames(List<Frame> frames, Callback callback)
    {
        if (flusher.offer(frames, callback))
            flusher.iterate();
    }

    @Override
    public String getId()
    {
        return "";
    }

    @Override
    public abstract long newStreamId(boolean bidirectional);

    @Override
    public Stream newStream(long streamId, Stream.Listener listener)
    {
        QuicStream stream = createLocalStream(streamId);
        stream.setListener(listener);
        return stream;
    }

    private QuicStream createLocalStream(long streamId)
    {
        QuicStream stream = new QuicStream(this, streamId, true);
        if (streams.putIfAbsent(streamId, stream) == null)
        {
            stream.setIdleTimeout(getQuicConfiguration().getStreamIdleTimeout());
            Long maxData = transportParameters.get(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE);
            if (maxData != null)
                stream.updateSendMaxData(maxData);

            if (LOG.isDebugEnabled())
                LOG.debug("created local {} on {}", stream, this);

            return stream;
        }
        throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "duplicate_local_stream");
    }

    private QuicStream getOrCreateRemoteStream(Frame.WithStreamId frame)
    {
        long streamId = frame.streamId();

        QuicStream stream;
        try (var _ = lock.lock())
        {
            // TODO: check close state.

            stream = streams.get(streamId);
            if (stream != null)
                return stream;

            // Create a new stream, if allowed.
            boolean bidirectional = StreamId.isBidirectional(streamId);
            AtomicLong remoteStreamCount = bidirectional ? biRemoteStreamCount : uniRemoteStreamCount;
            AtomicLong remoteStreamMaxCount = bidirectional ? biRemoteStreamMaxCount : uniRemoteStreamMaxCount;
            long max = remoteStreamMaxCount.get();
            long count = remoteStreamCount.get();
            if (max > 0 && count >= max)
                throw new QuicException(ErrorCode.STREAM_LIMIT_ERROR, "remote_stream_count_exceeded");
            remoteStreamCount.incrementAndGet();

            stream = new QuicStream(this, streamId, false);
            streams.put(streamId, stream);
            if (LOG.isDebugEnabled())
                LOG.debug("created remote {} on {}", stream, this);
        }

        stream.setIdleTimeout(getQuicConfiguration().getStreamIdleTimeout());

        Long maxData = transportParameters.get(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL);
        if (maxData != null)
            stream.updateSendMaxData(maxData);

        Stream.Listener listener = notifyNewStream(frame);
        stream.setListener(listener);
        stream.onNewStream(frame);

        return stream;
    }

    @Override
    public QuicStream getStream(long streamId)
    {
        return streams.get(streamId);
    }

    @Override
    public Collection<Stream> getStreams()
    {
        return streams.values().stream()
            .map(Stream.class::cast)
            .toList();
    }

    boolean remove(QuicStream stream)
    {
        boolean removed = streams.remove(stream.getId()) != null;
        if (LOG.isDebugEnabled())
            LOG.debug("removed {} {} from {}", removed, stream, this);
        return removed;
    }

    @Override
    public void maxStreams(MaxStreamsFrame frame, Promise.Invocable<Session> promise)
    {
        if (flusher.offer(List.of(frame), Promise.Invocable.toCallback(promise, this)))
            flusher.iterate();
    }

    @Override
    public void ping(Promise.Invocable<Session> promise)
    {
        if (flusher.offer(List.of(new PingFrame()), Promise.Invocable.toCallback(promise, this)))
            flusher.iterate();
    }

    @Override
    public void maxData(MaxDataFrame frame, Promise.Invocable<Session> promise)
    {
        if (flusher.offer(List.of(frame), Promise.Invocable.toCallback(promise, this)))
            flusher.iterate();
    }

    void data(QuicStream stream, StreamFrame frame, Promise.Invocable<Stream> promise)
    {
        if (flusher.offer(stream, List.of(frame), Promise.Invocable.toCallback(promise, stream)))
            flusher.iterate();
    }

    void maxData(QuicStream stream, StreamMaxDataFrame frame, Promise.Invocable<Stream> promise)
    {
        if (flusher.offer(List.of(frame), Promise.Invocable.toCallback(promise, stream)))
            flusher.iterate();
    }

    void reset(QuicStream stream, ResetFrame frame, Promise.Invocable<Stream> promise)
    {
        if (flusher.offer(List.of(frame), Promise.Invocable.toCallback(promise, stream)))
            flusher.iterate();
    }

    void stopSending(QuicStream stream, StopSendingFrame frame, Promise.Invocable<Stream> promise)
    {
        if (flusher.offer(List.of(frame), Promise.Invocable.toCallback(promise, stream)))
            flusher.iterate();
    }

    void dataBlocked(QuicStream stream, StreamDataBlockedFrame frame, Promise.Invocable<Stream> promise)
    {
        if (flusher.offer(List.of(frame), Promise.Invocable.toCallback(promise, stream)))
            flusher.iterate();
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
                // UDP datagrams should contain one or more full packets.
                // If they don't, then it's the other peer sending badly
                // encoded packets, so we just disconnect.
                buffer.skip(buffer.remaining());
                QuicException quicException = new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_packet");
                ConnectionCloseFrame frame = new ConnectionCloseFrame(quicException.getErrorCode().code(), quicException.getMessage(), 0);
                disconnect(frame, quicException, Promise.Invocable.noop());
                return;
            }

            switch (packet)
            {
                case InitialPacket initialPacket -> setDestinationConnectionId(initialPacket.sourceConnectionId());
                case RetryPacket retryPacket -> setDestinationConnectionId(retryPacket.sourceConnectionId());
                default ->
                {
                }
            }

            // The packet was fully decrypted and parsed, ack it.
            // Processing of frames by a different layer (such as the TLS layer or
            // the application layer) is independent of acks at the transport layer.
            ack(packet);

            if (packet instanceof InitialPacket || Arrays.equals(getSourceConnectionId(), packet.destinationConnectionId()))
            {
                notifyIncomingPacket(packet);
                continue;
            }

            // RFC-9000[7.2]: the packet must be discarded
            // if the packet dcid does not match.
            if (LOG.isDebugEnabled())
                LOG.debug("packet {} does not match connection id on {}", packet, this);
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
                processFrames(initialPacket);
            }
            case HandshakePacket handshakePacket ->
            {
                getTLSEngine().getPacketProtector().discardKeys(EncryptionLevel.INITIAL);
                processFrames(handshakePacket);
            }
            case ZeroRTTPacket zeroRTTPacket ->
            {
                // TODO:
                processFrames(zeroRTTPacket);
            }
            case OneRTTPacket oneRTTPacket ->
            {
                // TODO: handle here keyPhase shift?
                processFrames(oneRTTPacket);
            }
            // RetryPacket and VersionNegotiationPacket only handled by clients.
            default -> throw new UnsupportedOperationException();
        }
    }

    private void processFrames(Packet.WithFrames packet)
    {
        for (Frame frame : packet.frames())
        {
            processFrame(packet, frame);
        }
    }

    protected void processFrame(Packet.WithFrames packet, Frame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} in {} on {}", frame, packet, this);
        switch (frame)
        {
            case AckFrame ackFrame ->
            {
                // TODO: notify reliability data structure.
            }
            case CryptoFrame cryptoFrame ->
            {
                EncryptionLevel encryptionLevel = EncryptionLevel.from(packet);
                cryptoStreams.computeIfAbsent(encryptionLevel, _ -> new FrameStream(this::processCryptoFrame)).offer(cryptoFrame);
            }
            case MaxDataFrame maxDataFrame ->
            {
                flusher.offer(maxDataFrame);
            }
            case Frame.WithStreamId withStreamId -> processWithStreamId(packet, withStreamId);
            default ->
            {
                // TODO: notify Session.Listener
            }
        }
    }

    private void processWithStreamId(Packet.WithFrames packet, Frame.WithStreamId frame)
    {
        QuicStream stream = getOrCreateRemoteStream(frame);
        stream.processFrame(frame);
    }

    public void updateSendMaxData(QuicStream stream, long newValue)
    {
        if (stream == null)
        {
            if (Atomics.updateMax(sendMaxData, newValue))
                writeStalled = false;
        }
        else
        {
            stream.updateSendMaxData(newValue);
        }
    }

    public long getSendWindow(QuicStream stream)
    {
        if (stream == null)
            return sendMaxData.get() - sendData.get();
        else
            return stream.getSendWindow();
    }

    public long getSendData(QuicStream stream)
    {
        if (stream == null)
            return sendData.get();
        else
            return stream.getSendData();
    }

    public void updateSendData(QuicStream stream, long sent)
    {
        sendData.addAndGet(sent);
        if (stream != null)
            stream.updateSendData(sent);
    }

    public boolean stall()
    {
        boolean result = !writeStalled;
        writeStalled = true;
        return result;
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

    protected void processTransportParameters(TransportParameters transportParameters)
    {
        this.transportParameters = transportParameters;
        Long maxData = transportParameters.get(TransportParameters.Ids.INITIAL_MAX_DATA);
        if (maxData != null)
            updateSendMaxData(null, maxData);

        // TODO: other parameters.
    }

    private void ack(Packet packet)
    {
        if (packet instanceof Packet.WithFrames p && p.requiresAcknowledgement())
        {
            // TODO: notify reliability data structure?
            //  Or leave that only for sent packets and received acks?
            AckFrame ackFrame = new AckFrame(p.packetNumber(), 0, 0, List.of());
            EncryptionLevel encryptionLevel = EncryptionLevel.from(packet);
            if (flusher.offer(encryptionLevel, List.of(ackFrame), Callback.NOOP/*TODO*/))
                flusher.iterate();
        }
    }

    protected void packet(Packet packet)
    {
        if (flusher.offer(packet, Callback.NOOP))
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

    protected void sendTLSMessages(EncryptionLevel encryptionLevel, List<Message> messages, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sending TLS messages {} on {}", messages, this);

        // TODO: why the messages need to be generated here?
        //  Perhaps I can have a version of CryptoFrame that carries the messages.
        //  When parsing, it must be a RBB because they can be out of order.
        //  But for generation, should not be necessary.
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(getByteBufferPool(), getQuicConfiguration().isUseOutputDirectByteBuffers(), -1, 0, 0);
        try
        {
            for (Message message : messages)
            {
                getTLSEngine().getMessagesGenerator().generate(accumulator, message);
            }
            // Always use offset 0, as it will be calculated properly when flushing the frame.
            CryptoFrame cryptoFrame = new CryptoFrame(0, accumulator);
            crypto(encryptionLevel, cryptoFrame, callback);
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

    public void notifyMaxData(QuicStream stream, long maxData)
    {
        if (stream == null)
            notifyMaxData(new MaxDataFrame(maxData));
        else
            stream.processFrame(new StreamMaxDataFrame(stream.getId(), maxData));
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
