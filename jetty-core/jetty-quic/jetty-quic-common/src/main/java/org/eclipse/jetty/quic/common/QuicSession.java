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
import java.nio.channels.ClosedChannelException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.HandshakeDoneFrame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.NewConnectionIdFrame;
import org.eclipse.jetty.quic.api.frames.NewTokenFrame;
import org.eclipse.jetty.quic.api.frames.PaddingFrame;
import org.eclipse.jetty.quic.api.frames.PathChallengeFrame;
import org.eclipse.jetty.quic.api.frames.PathResponseFrame;
import org.eclipse.jetty.quic.api.frames.PingFrame;
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.RetireConnectionIdFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.frames.FrameStream;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.QuicFlusher;
import org.eclipse.jetty.quic.common.internal.packets.PacketsParser;
import org.eclipse.jetty.quic.common.internal.packets.RetryPacketGenerator;
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
import org.eclipse.jetty.tls.TLSException;
import org.eclipse.jetty.util.Atomics;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

/// A logical connection with a remote peer.
public abstract class QuicSession extends AbstractSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final AutoLock lock = new AutoLock();
    private final RetryPacketGenerator retryPacketGenerator = new RetryPacketGenerator();
    private final Map<EncryptionLevel, FrameStream> cryptoStreams = new HashMap<>();
    private final Map<Long, QuicStream> streams = new ConcurrentHashMap<>();
    private final AtomicLong biStreamIds = new AtomicLong();
    private final AtomicLong uniStreamIds = new AtomicLong();
    private final AtomicLong biRemoteStreamCount = new AtomicLong();
    private final AtomicLong biRemoteStreamMaxCount = new AtomicLong();
    private final AtomicLong uniRemoteStreamCount = new AtomicLong();
    private final AtomicLong uniRemoteStreamMaxCount = new AtomicLong();
    private final AtomicLong terminatedBiLocalStream = new AtomicLong(-1);
    private final AtomicLong terminatedUniLocalStream = new AtomicLong(-1);
    private final AtomicLong terminatedBiRemoteStream = new AtomicLong(-1);
    private final AtomicLong terminatedUniRemoteStream = new AtomicLong(-1);
    private final AtomicLong sendData = new AtomicLong();
    private final AtomicLong sendMaxData = new AtomicLong();
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final QuicConnection connection;
    private final PacketTracker packetTracker;
    private final PacketNumbers packetNumbers;
    private final TLSEngine tlsEngine;
    private final boolean client;
    private final StreamTimeouts streamTimeouts;
    private final PacketsParser parser;
    private final QuicFlusher flusher;
    private CloseState closeState = CloseState.NOT_CLOSED;
    private Packet.Listener packetListener;
    private QuicVersion quicVersion;
    private byte[] origDstConnectionId;
    private byte[] dstConnectionId;
    private byte[] srcConnectionId;
    private long idleTimeout;
    private SocketAddress remoteSocketAddress;
    private TransportParameters transportParameters;
    private boolean writeStalled;
    private Scheduler.Task keepAliveTask;

    protected QuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicConfiguration quicConfiguration, QuicConnection connection, PacketTracker packetTracker, PacketNumbers packetNumbers, TLSEngine tlsEngine, Session.Listener listener, boolean client)
    {
        super(executor, quicConfiguration, listener);
        this.scheduler = scheduler;
        installBean(scheduler);
        this.byteBufferPool = byteBufferPool;
        installBean(byteBufferPool);
        this.connection = connection;
        installBean(connection);
        this.packetTracker = packetTracker;
        installBean(packetTracker);
        this.packetNumbers = packetNumbers;
        installBean(packetNumbers);
        this.tlsEngine = tlsEngine;
        installBean(tlsEngine);
        this.client = client;
        this.streamTimeouts = new StreamTimeouts(scheduler);
        installBean(streamTimeouts);
        this.parser = new PacketsParser(tlsEngine.getPacketProtector(), packetNumbers, new FramesParser());
        installBean(parser);
        this.flusher = new QuicFlusher(this);
        installBean(flusher);
        this.packetListener = new PacketProcessor();
        this.dstConnectionId = BufferUtil.EMPTY_BYTES;
        this.srcConnectionId = tlsEngine.newRandomBytes(8);
        this.keepAliveTask = () -> false;
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

    public CongestionController getCongestionController()
    {
        return packetTracker.getCongestionController();
    }

    public PacketTracker getPacketTracker()
    {
        return packetTracker;
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
        return getQuicConnection().getEndPoint();
    }

    public QuicVersion getQuicVersion()
    {
        return quicVersion;
    }

    public void setQuicVersion(QuicVersion quicVersion)
    {
        this.quicVersion = quicVersion;
    }

    public byte[] getOriginalDestinationConnectionId()
    {
        return origDstConnectionId;
    }

    protected void setOriginalDestinationConnectionId(byte[] origDstConnectionId)
    {
        this.origDstConnectionId = origDstConnectionId;
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

    public String getApplicationProtocol()
    {
        return getTLSEngine().getApplicationProtocol();
    }

    public boolean isKeepAliveEnabled()
    {
        return keepAliveTask != null;
    }

    public void setKeepAliveEnabled(boolean keepAlive)
    {
        if (keepAliveTask != null)
            keepAliveTask.cancel();

        if (keepAlive)
            scheduleKeepAlive();
        else
            keepAliveTask = null;
    }

    @Override
    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
        scheduleKeepAlive();
    }

    private void scheduleKeepAlive()
    {
        long timeout = getIdleTimeout();
        if (timeout > 0 && isKeepAliveEnabled())
        {
            keepAliveTask.cancel();
            keepAliveTask = getScheduler().schedule(() -> getExecutor().execute(this::sendKeepAlive), timeout / 2, MILLISECONDS);
        }
    }

    private void sendKeepAlive()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sending keepalive on {}", this);
        // TODO: hardcoded EncryptionLevel
        sendProbe(EncryptionLevel.ONE_RTT);
        scheduleKeepAlive();
    }

    protected abstract void notIdle();

    public boolean onIdleTimeout(TimeoutException timeout)
    {
        ThreadPool.executeImmediately(getExecutor(), () ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("idle timeout expired on {}", this);

            // RFC-9000[10]: QUIC idle timeouts are fatal and cannot be ignored.
            // We use a keep-alive mechanism to avoid that idle timouts fire.
            notifyFailure(timeout);

            // RFC-9000[10.1]: the idle timeout should close the
            // connection silently, but we send a ConnectionCloseFrame
            // to inform the other peer that the connection is broken.
            disconnect(new ConnectionCloseFrame(ErrorCode.NO_ERROR.code(), "idle_timeout"), timeout, Promise.Invocable.noop());
        });
        return false;
    }

    public abstract int getUDPPayloadLength();

    private boolean isOpen()
    {
        try (var _ = lock.lock())
        {
            return closeState == CloseState.NOT_CLOSED;
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        // TODO: handle external stop.
        //  streamTimeouts.destroy().

        if (keepAliveTask != null)
            keepAliveTask.cancel();

        super.doStop();
    }

    void scheduleTimeout(QuicStream stream)
    {
        streamTimeouts.schedule(stream);
    }

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
    private void crypto(EncryptionLevel encryptionLevel, CryptoFrame frame, Callback callback)
    {
        // TODO: check closeState.
        flusher.sendFrames(encryptionLevel, List.of(frame), callback);
    }

    protected void resetCrypto()
    {
        flusher.resetCrypto();
    }

    protected void frames(List<Frame> frames, Callback callback)
    {
        flusher.sendFrames(EncryptionLevel.ONE_RTT, frames, callback);
    }

    @Override
    public String getId()
    {
        return "";
    }

    @Override
    public long newStreamId(boolean bidirectional)
    {
        AtomicLong streamIds = bidirectional ? biStreamIds : uniStreamIds;
        return StreamId.newStreamId(streamIds.getAndIncrement(), bidirectional, client);
    }

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
            // TODO: check session close state.

            stream = streams.get(streamId);
            if (stream != null)
                return stream;

            // A local stream id cannot create a new remote stream.
            // For example, a client can only receive a frame on a
            // client-initiated stream that the client created.
            if (StreamId.isLocal(streamId, client))
            {
                AtomicLong streamIds = StreamId.isBidirectional(streamId) ? biStreamIds : uniStreamIds;
                if (streamId > streamIds.get())
                    throw new QuicException(ErrorCode.STREAM_STATE_ERROR, "invalid_stream_id");
                else
                    return null;
            }

            boolean bidirectional = StreamId.isBidirectional(streamId);
            AtomicLong terminatedStreamId = bidirectional ? terminatedBiRemoteStream : terminatedUniRemoteStream;
            boolean terminated = streamId <= terminatedStreamId.get();
            if (terminated)
                return null;

            // Create a new stream, if allowed.
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
                LOG.debug("created remote {} for {} on {}", stream, frame, this);
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
        long streamId = stream.getId();
        boolean removed = streams.remove(streamId) != null;
        if (LOG.isDebugEnabled())
            LOG.debug("removed {} {} from {}", removed, stream, this);
        if (removed)
        {
            AtomicLong terminated = stream.isBidirectional()
                ? stream.isLocal() ? terminatedBiLocalStream : terminatedBiRemoteStream
                : stream.isLocal() ? terminatedUniLocalStream : terminatedUniRemoteStream;
            Atomics.updateMax(terminated, streamId);
        }
        return removed;
    }

    @Override
    public void maxStreams(MaxStreamsFrame frame, Promise.Invocable<Session> promise)
    {
        Callback callback = Promise.Invocable.toCallback(promise, this);
        sendFrames(EncryptionLevel.ONE_RTT, List.of(frame), callback);
    }

    @Override
    public void ping(Promise.Invocable<Session> promise)
    {
        List<Frame> frames = List.of(PingFrame.INSTANCE);
        sendFrames(EncryptionLevel.ONE_RTT, frames, Promise.Invocable.toCallback(promise, this));
    }

    @Override
    public void maxData(MaxDataFrame frame, Promise.Invocable<Session> promise)
    {
        Callback callback = Promise.Invocable.toCallback(promise, this);
        sendFrames(EncryptionLevel.ONE_RTT, List.of(frame), callback);
    }

    public void data(QuicStream stream, StreamFrame frame, Promise.Invocable<Stream> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sending data {} on {} on {}", frame, stream, this);
        flusher.sendFrames(stream, List.of(frame), Promise.Invocable.toCallback(promise, stream));
    }

    void maxData(QuicStream stream, StreamMaxDataFrame frame, Promise.Invocable<Stream> promise)
    {
        Callback callback = Promise.Invocable.toCallback(promise, stream);
        sendFrames(EncryptionLevel.ONE_RTT, List.of(frame), callback);
    }

    public void reset(QuicStream stream, ResetFrame frame, Promise.Invocable<Stream> promise)
    {
        Callback callback = Promise.Invocable.toCallback(promise, stream);
        sendFrames(EncryptionLevel.ONE_RTT, List.of(frame), callback);
    }

    void stopSending(QuicStream stream, StopSendingFrame frame, Promise.Invocable<Stream> promise)
    {
        Callback callback = Promise.Invocable.toCallback(promise, stream);
        sendFrames(EncryptionLevel.ONE_RTT, List.of(frame), callback);
    }

    void dataBlocked(QuicStream stream, StreamDataBlockedFrame frame, Promise.Invocable<Stream> promise)
    {
        Callback callback = Promise.Invocable.toCallback(promise, stream);
        sendFrames(EncryptionLevel.ONE_RTT, List.of(frame), callback);
    }

    void sendFrames(EncryptionLevel encryptionLevel, List<Frame> frames, Callback callback)
    {
        if (isOpen())
            flusher.sendFrames(encryptionLevel, frames, callback);
        else
            callback.failed(new ClosedChannelException());
    }

    void sendProbe(EncryptionLevel encryptionLevel)
    {
        if (isOpen())
            flusher.sendProbe(encryptionLevel);
    }

    @Override
    public void disconnect(ConnectionCloseFrame frame, Throwable failure, Promise.Invocable<Session> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting {} on {}", frame, this);

        boolean disconnect;
        try (var _ = lock.lock())
        {
            disconnect = switch (closeState)
            {
                case NOT_CLOSED ->
                {
                    closeState = CloseState.CLOSING;

                    // RFC-9000[10.2]: closing and draining states
                    // should persist for 3 times the current PTO.
                    RTTData rttData = getPacketTracker().getRTTData();
                    long pto = rttData.smoothedRTT() + 4 * rttData.variationRTT();
                    getScheduler().schedule(this::terminate, 3 * pto, NANOSECONDS);

                    yield true;
                }
                case CLOSING ->
                {
                    yield true;
                }
                case DRAINING, CLOSED ->
                {
                    // RFC-9002[10.2.2]: an endpoint in the
                    // draining state must not send any packets.
                    yield false;
                }
            };
        }

        if (disconnect)
        {
            // TODO: tear down all the streams.

            Callback callback = Promise.Invocable.toCallback(promise, this);
            // TODO: hardcoded EncryptionLevel
            flusher.sendFrames(EncryptionLevel.ONE_RTT, List.of(frame), Callback.from(callback, this::disconnectComplete));
        }
        else
        {
            promise.succeeded(this);
        }
    }

    private void disconnectComplete()
    {
        notifyDisconnect();
        dispose();
    }

    private void terminate()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("terminating {}", this);

        try (var _ = lock.lock())
        {
            closeState = CloseState.CLOSED;
        }

        getQuicConnection().terminate(this);
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
            try (Packet packet = parser.parse(buffer))
            {
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

                if (packet == Packet.DISCARD)
                    continue;

                notifyIncomingPacket(packet);
            }
        }
    }

    private void process(Packet packet)
    {
        packetNumbers.onPacketReceived(packet);

        // Minimally process first packets to set
        // the dcid be used by acknowledgments.
        switch (packet)
        {
            case InitialPacket initialPacket -> setDestinationConnectionId(initialPacket.sourceConnectionId());
            case RetryPacket retryPacket -> setDestinationConnectionId(retryPacket.sourceConnectionId());
            default ->
            {
            }
        }

        if (packet instanceof InitialPacket || Arrays.equals(getSourceConnectionId(), packet.destinationConnectionId()))
        {
            // Reset the idle timeout only on the receiving side, because:
            // - Computing when to reset the idle timeout on the sending side is complicated:
            //   must take into account only ack-eliciting packets, and only if they are sent
            //   after another packet has been received.
            // - Sending would trigger an ack within an RTT, which is much smaller (milliseconds)
            //   than the idle timeout (typically, seconds). In the rare case of sending when
            //   the idle timeout is about to expire, well - too bad.
            // - QUIC idle timeouts being fatal are likely disabled by the keepalive mechanism
            //   so the rare case above should never happen: the keepalive triggers an ack
            //   well within the idle timeout; in case of no ack, the connection is broken.
            notIdle();

            // The packet was fully decrypted and parsed, ack it now.
            // Processing of frames by a different layer (such as the
            // TLS layer or the application layer) is independent of
            // acknowledgments at the transport layer.
            acknowledge(packet);

            List<Invocable.Task> tasks = processPacket(packet);
            for (Invocable.Task task : tasks)
            {
                connection.offerTask(task);
            }
        }
        else
        {
            // RFC-9000[7.2]: the packet must be discarded
            // if the packet dcid does not match.
            if (LOG.isDebugEnabled())
                LOG.debug("packet {} does not match connection id on {}", packet, this);
        }
    }

    protected List<Invocable.Task> processPacket(Packet packet)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("processing {} on {}", packet, this);

            return switch (packet)
            {
                case InitialPacket initialPacket ->
                {
                    yield processFrames(initialPacket);
                }
                case HandshakePacket handshakePacket ->
                {
                    getTLSEngine().getPacketProtector().discardKeys(EncryptionLevel.INITIAL);
                    yield processFrames(handshakePacket);
                }
                case ZeroRTTPacket zeroRTTPacket ->
                {
                    // TODO:
                    yield processFrames(zeroRTTPacket);
                }
                case OneRTTPacket oneRTTPacket ->
                {
                    // TODO: handle here keyPhase shift?
                    yield processFrames(oneRTTPacket);
                }
                // RetryPacket and VersionNegotiationPacket only handled by clients.
                default -> throw new UnsupportedOperationException();
            };
        }
        catch (Throwable x)
        {
            fail(x);
            return List.of();
        }
    }

    private List<Invocable.Task> processFrames(Packet.WithFrames packet)
    {
        // Group the frames by stream id.
        List<Frame> noStreamFrames = null;
        LinkedHashMap<Long, List<Frame.WithStreamId>> groups = null;
        List<Frame> frames = packet.frames();
        for (Frame frame : frames)
        {
            if (frame instanceof Frame.WithStreamId wid)
            {
                if (groups == null)
                    groups = new LinkedHashMap<>();
                groups.computeIfAbsent(wid.streamId(), _ -> new ArrayList<>()).add(wid);
            }
            else
            {
                if (noStreamFrames == null)
                    noStreamFrames = new ArrayList<>();
                noStreamFrames.add(frame);
            }
        }

        if (noStreamFrames != null)
        {
            for (Frame frame : noStreamFrames)
            {
                processFrame(packet,  frame);
            }
        }

        if (groups == null)
            return List.of();

        if (groups.size() == 1)
        {
            Map.Entry<Long, List<Frame.WithStreamId>> entry = groups.firstEntry();
            Invocable.Task task = processStreamFrames(entry.getValue());
            return task == null ? List.of() : List.of(task);
        }

        List<Invocable.Task> tasks = null;
        for (Map.Entry<Long, List<Frame.WithStreamId>> entry : groups.entrySet())
        {
            Invocable.Task task = processStreamFrames(entry.getValue());
            if (task != null)
            {
                if (tasks == null)
                    tasks = new  ArrayList<>();
                tasks.add(task);
            }
        }
        return tasks == null ? List.of() : tasks;
    }

    protected void processFrame(Packet.WithFrames packet, Frame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} in {} on {}", frame, packet, this);
        switch (frame)
        {
            case AckFrame ackFrame -> processAckFrame(EncryptionLevel.from(packet), ackFrame);
            case CryptoFrame cryptoFrame ->
            {
                EncryptionLevel encryptionLevel = EncryptionLevel.from(packet);
                cryptoStreams.computeIfAbsent(encryptionLevel, _ -> new FrameStream(this::processCryptoFrame)).offer(cryptoFrame);
            }
            case MaxDataFrame maxDataFrame ->
            {
                // Serialize processing of maxData frames through the flusher.
                flusher.processMaxData(maxDataFrame);
                // TODO: notify Session.Listener (before or after queuing to the flusher?)
            }
            case ConnectionCloseFrame connectionCloseFrame -> processConnectionCloseFrame(connectionCloseFrame);
            default ->
            {
                // TODO: notify Session.Listener
            }
        }
    }

    private void processAckFrame(EncryptionLevel encryptionLevel, AckFrame frame)
    {
        packetNumbers.onAckFrameReceived(encryptionLevel, frame);
        flusher.onAckFrameReceived(encryptionLevel, frame);
    }

    private void processConnectionCloseFrame(ConnectionCloseFrame frame)
    {
        boolean process;
        boolean disconnect = false;
        try (var _ = lock.lock())
        {
            process = switch (closeState)
            {
                case NOT_CLOSED ->
                {
                    closeState = CloseState.DRAINING;

                    // RFC-9000[10.2]: closing and draining states
                    // should persist for 3 times the current PTO.
                    RTTData rttData = getPacketTracker().getRTTData();
                    long pto = rttData.smoothedRTT() + 4 * rttData.variationRTT();
                    getScheduler().schedule(this::terminate, 3 * pto, NANOSECONDS);

                    disconnect = true;
                    yield true;
                }
                case CLOSING ->
                {
                    closeState = CloseState.DRAINING;
                    yield true;
                }
                case DRAINING, CLOSED -> false;
            };
        }

        if (process)
        {
            // TODO: tear down all the streams.

            notifyConnectionClose(frame);
            if (disconnect)
            {
                // TODO: hardcoded EncryptionLevel
                ConnectionCloseFrame reply = new ConnectionCloseFrame(ErrorCode.NO_ERROR.code(), null, 0x1C);
                flusher.sendFrames(EncryptionLevel.ONE_RTT, List.of(reply), Callback.from(this::disconnectComplete));
            }
        }
    }

    private Invocable.Task processStreamFrames(List<Frame.WithStreamId> frames)
    {
        Frame.WithStreamId frame = frames.getFirst();
        QuicStream stream = getOrCreateRemoteStream(frame);
        if (stream != null)
            return stream.processFrames(frames);

        if (LOG.isDebugEnabled())
            LOG.debug("dropping frame {} for terminated stream #{} on {}", frame, frame.streamId(), this);
        return null;
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

    private void processCryptoFrame(Frame.WithData frame)
    {
        try
        {
            while (frame.remaining() > 0)
            {
                Message message = frame.map(data -> getTLSEngine().getMessagesParser().parse(data));
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
        // TODO: apply verifications to TransportParameters as per RFC.
        // TODO: QuicTransports must be present and validated:
        //  * No forbidden parameters are present
        //  * No duplicates
        //  * Values are within allowed ranges
        //  Apply Quic transport params to the various components.

        this.transportParameters = transportParameters;

        // RFC-9000[10.1]: the idle timeout is the minimum of the two advertised values.
        Long remoteIdleTimeout = transportParameters.get(TransportParameters.Ids.MAX_IDLE_TIMEOUT);
        if (remoteIdleTimeout != null && remoteIdleTimeout > 0)
        {
            long localIdleTimeout = getIdleTimeout();
            if (localIdleTimeout > 0)
                setIdleTimeout(Math.min(localIdleTimeout, remoteIdleTimeout));
            else
                setIdleTimeout(remoteIdleTimeout);
        }

        Long maxData = transportParameters.get(TransportParameters.Ids.INITIAL_MAX_DATA);
        if (maxData != null)
            updateSendMaxData(null, maxData);

        Long ackMaxDelay = transportParameters.get(TransportParameters.Ids.MAX_ACK_DELAY);
        if (ackMaxDelay != null)
            packetTracker.setAcknowledgmentMaxDelay(ackMaxDelay);
        Long ackDelayExponent = transportParameters.get(TransportParameters.Ids.ACK_DELAY_EXPONENT);
        if (ackDelayExponent != null)
            packetTracker.setAcknowledgmentDelayExponent(ackDelayExponent);

        // TODO: other parameters.

        notifyTransportParameters(transportParameters);
    }

    private void acknowledge(Packet packet)
    {
        if (packet instanceof Packet.WithFrames p && p.requiresAcknowledgement())
            flusher.sendAcknowledgment(p, Callback.NOOP/*TODO*/);
    }

    public void packet(Packet packet, Callback callback)
    {
        flusher.sendPacket(packet, callback);
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
            // The offset will be calculated properly when flushing the frame.
            CryptoFrame cryptoFrame = new CryptoFrame(-1, accumulator);
            crypto(encryptionLevel, cryptoFrame, callback);
            accumulator.release();
        }
        catch (Throwable x)
        {
            accumulator.release();
            callback.failed(x);
        }
    }

    void onScheduledTask(Runnable task)
    {
        flusher.onScheduledTask(task);
    }

    private void notifyIncomingPacket(Packet packet)
    {
        try
        {
            packetListener.onIncomingPacket(this, packet);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", packetListener, x);
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
            LOG.info("failure while notifying listener {}", packetListener, x);
        }
    }

    protected void generateRetryPacket(RetainableByteBuffer.Mutable retryAccumulator, RetryPacket retryPacket)
    {
        retryPacketGenerator.generate(retryAccumulator, retryPacket);
    }

    public void retransmit(List<Packet.WithFrames> packets)
    {
        // TODO: retransmissions should have higher priority than normal transmissions.
        //  This means that either we need some private API such as QuicStream.retransmitData()
        //  or we need to unwrap that and call flusher.prepend() + flusher.iterate().

        if (LOG.isDebugEnabled())
            LOG.debug("retransmitting {} on {}", packets, this);

        Map<EncryptionLevel, List<Frame>> groups = packets.stream()
            .collect(Collectors.groupingBy(EncryptionLevel::from,
                Collectors.flatMapping(p -> p.frames().stream(), Collectors.toList())));

        for (Map.Entry<EncryptionLevel, List<Frame>> entry : groups.entrySet())
        {
            EncryptionLevel encryptionLevel = entry.getKey();
            for (Frame frame : entry.getValue())
            {
                switch (frame)
                {
                    case AckFrame _,
                         ConnectionCloseFrame _,
                         PaddingFrame _,
                         PathResponseFrame _,
                         PingFrame _ ->
                    {
                        // These frames are not retransmitted.
                    }
                    case CryptoFrame cryptoFrame ->
                    {
                        // TODO: only retransmit if the keys for the EncryptionLevel are available.
                        cryptoFrame.rewind();
                        crypto(encryptionLevel, cryptoFrame, Callback.NOOP);
                    }
                    case DataBlockedFrame dataBlockedFrame ->
                    {
                        // TODO: only resend if still blocked.
                    }
                    case HandshakeDoneFrame handshakeDoneFrame ->
                    {
                        // TODO: retransmit as-is.
                    }
                    case MaxDataFrame maxDataFrame ->
                    {
                        // TODO: there is an API for maxData(), but not sure I want to expose it to applications.
                        //  Flow control is not implemented yet; maxData() is to tell the other peer that it can
                        //  send more data. However, this should not be done by applications, but by the flow
                        //  control mechanism: when approaching the max, it can decide to either close the
                        //  connection, or send a maxData, which we should remember here (on in the flow control
                        //  component) to be able to send the most updated value.
                    }
                    case MaxStreamsFrame maxStreamsFrame ->
                    {
                        // TODO: see discussion in MAX_DATA: we need a strategy mechanism similar to flow control
                        //  to decide whether to close the connection, or allow more streams.
                    }
                    case NewConnectionIdFrame newConnectionIdFrame ->
                    {
                        // TODO: retransmit as-is.
                    }
                    case NewTokenFrame newTokenFrame ->
                    {
                        // TODO: retransmit as-is.
                    }
                    case PathChallengeFrame pathChallengeFrame ->
                    {
                        // TODO: payload must be refreshed.
                    }
                    case ResetFrame resetFrame ->
                    {
                        // TODO: reset() sets the stream to locally closed after the send.
                        //  However, we should think about making it so after we received
                        //  and ack for it?
                        QuicStream stream = getStream(resetFrame.streamId());
                        if (stream != null && !stream.isLocallyClosed())
                            stream.reset(resetFrame.applicationErrorCode(), Promise.Invocable.noop());
                    }
                    case RetireConnectionIdFrame retireConnectionIdFrame ->
                    {
                        // TODO: retransmit as-is.
                    }
                    case StopSendingFrame stopSendingFrame ->
                    {
                        // TODO: Same comment as reset().
                        QuicStream stream = getStream(stopSendingFrame.streamId());
                        if (stream != null && !stream.isRemotelyClosed())
                            stream.stopSending(stopSendingFrame.applicationErrorCode(), Promise.Invocable.noop());
                    }
                    case StreamDataBlockedFrame streamDataBlockedFrame ->
                    {
                        // TODO: only resend if still blocked.
                    }
                    case StreamFrame streamFrame ->
                    {
                        QuicStream stream = getStream(streamFrame.streamId());
                        if (stream != null)
                        {
                            streamFrame.rewind();
                            // Bypass stream.data() since the stream may be writing
                            // and this additional write would cause WritePendingException.
                            data(stream, streamFrame, Promise.Invocable.noop());
                        }
                        else
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("could not retransmit {}, no stream on {}", streamFrame, this);
                        }
                    }
                    case StreamMaxDataFrame streamMaxDataFrame ->
                    {
                        // TODO: see MAX_DATA, with the additional check that the stream must not be remotely closed,
                        //  see RFC-9000[13.3]
                    }
                    case StreamsBlockedFrame streamsBlockedFrame ->
                    {
                        // TODO: only resend if still blocked.
                    }
                }
            }
        }
    }

    public void fail(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failure on {}", this, x);

        ConnectionCloseFrame frame = switch (x)
        {
            case TLSException tls ->
            {
                // RFC-9000[20.1]: convert TLS alerts into CRYPTO_ERRORs.
                long code = ErrorCode.CRYPTO_ERROR.code() + tls.getAlert().code();
                yield new ConnectionCloseFrame(code, x.getMessage(), 0x06);
            }
            case QuicException quic ->
                new ConnectionCloseFrame(quic.getErrorCode().code(), quic.getMessage(), quic.getFrameType());
            default -> new ConnectionCloseFrame(ErrorCode.INTERNAL_ERROR.code(), x.getMessage(), 0x00);
        };

        notifyFailure(x);

        disconnect(frame, x, Promise.Invocable.noop());
    }

    public void dispose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disposing {}", this);
        // TODO: dispose all components, that may have allocated a buffer or such
        //  For example TranscriptHash.dispose()
    }

    @Override
    public String toString()
    {
        return "%s[%s,dcid=%s,streams=%d]".formatted(super.toString(), closeState, StringUtil.toHexString(getDestinationConnectionId()), streams.size());
    }

    private class PacketProcessor implements Packet.Listener
    {
        @Override
        public void onIncomingPacket(Session session, Packet packet)
        {
            process(packet);
        }
    }

    private class StreamTimeouts extends CyclicTimeouts<QuicStream>
    {
        private StreamTimeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<QuicStream> iterator()
        {
            return streams.values().iterator();
        }

        @Override
        protected boolean onExpired(QuicStream stream)
        {
            getExecutor().execute(() -> stream.onIdleTimeout(new TimeoutException("Idle timeout " + stream.getIdleTimeout() + " ms elapsed")));
            return false;
        }
    }

    private enum CloseState
    {
        NOT_CLOSED,
        CLOSING,
        DRAINING,
        CLOSED
    }
}
