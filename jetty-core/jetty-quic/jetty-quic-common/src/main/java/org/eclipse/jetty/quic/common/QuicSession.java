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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RateControl;
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
import org.eclipse.jetty.quic.api.frames.ResetStreamFrame;
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
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.ReadyTask;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.ACK_DELAY_EXPONENT;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.ACTIVE_CONNECTION_ID_LIMIT;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.INITIAL_MAX_DATA;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.INITIAL_MAX_STREAMS_UNIDIRECTIONAL;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.INITIAL_SOURCE_CONNECTION_ID;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.MAX_ACK_DELAY;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.MAX_IDLE_TIMEOUT;
import static org.eclipse.jetty.quic.api.frames.TransportParameters.Ids.MAX_UDP_PAYLOAD_SIZE;
import static org.eclipse.jetty.util.thread.Invocable.InvocationType.NON_BLOCKING;

/// A logical connection with a remote peer.
public abstract class QuicSession extends AbstractSession
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicSession.class);

    private final AutoLock lock = new AutoLock();
    private final RetryPacketGenerator retryPacketGenerator = new RetryPacketGenerator();
    private final Map<EncryptionLevel, FrameStream> cryptoStreams = new HashMap<>();
    private final Map<Long, QuicStream> streams = new HashMap<>();
    private final AtomicLong recvBytes = new AtomicLong();
    private final AtomicLong sentBytes = new AtomicLong();
    private final AtomicLong biStreamIds = new AtomicLong();
    private final AtomicLong uniStreamIds = new AtomicLong();
    private final AtomicLong biLocalStreamCount = new AtomicLong();
    private final AtomicLong biLocalStreamMaxCount = new AtomicLong();
    private final AtomicLong uniLocalStreamCount = new AtomicLong();
    private final AtomicLong uniLocalStreamMaxCount = new AtomicLong();
    private final AtomicLong biRemoteStreamCount = new AtomicLong();
    private final AtomicLong biRemoteStreamMaxCount = new AtomicLong();
    private final AtomicLong uniRemoteStreamCount = new AtomicLong();
    private final AtomicLong uniRemoteStreamMaxCount = new AtomicLong();
    private final AtomicLong terminatedBiLocalStream = new AtomicLong(-1);
    private final AtomicLong terminatedUniLocalStream = new AtomicLong(-1);
    private final AtomicLong terminatedBiRemoteStream = new AtomicLong(-1);
    private final AtomicLong terminatedUniRemoteStream = new AtomicLong(-1);
    private final AtomicLong recvOffset = new AtomicLong();
    private final AtomicLong recvMaxOffset = new AtomicLong();
    private final AtomicLong sentOffset = new AtomicLong();
    private final AtomicLong sendMaxOffset = new AtomicLong();
    private final AtomicBoolean flowControlStalled = new AtomicBoolean();
    private final AtomicReference<ConnectionCloseFrame> closeFrame = new AtomicReference<>();
    private final Scheduler scheduler;
    private final ByteBufferPool byteBufferPool;
    private final QuicConnection connection;
    private final PacketTracker packetTracker;
    private final PacketNumbers packetNumbers;
    private final TLSEngine tlsEngine;
    private final RateControl rateControl;
    private final FlowController flowController;
    private final StreamsController streamsController;
    private final boolean client;
    private final PacketsParser parser;
    private final QuicFlusher flusher;
    private CloseState closeState = CloseState.NOT_CLOSED;
    private PacketListener packetListener;
    private QuicVersion quicVersion;
    private byte[] origDstConnectionId;
    private byte[] dstConnectionId;
    private byte[] srcConnectionId;
    private long idleTimeout;
    private SocketAddress remoteSocketAddress;
    private TransportParameters localTransportParameters;
    private TransportParameters remoteTransportParameters;
    private volatile boolean biLocalStreamsStalled;
    private volatile boolean uniLocalStreamsStalled;
    private Scheduler.Task keepAliveTask;
    private EncryptionLevel encryptionLevel = EncryptionLevel.INITIAL;
    private long closeBackoff;

    protected QuicSession(Executor executor, Scheduler scheduler, ByteBufferPool byteBufferPool, QuicConfiguration quicConfiguration, QuicConnection connection, PacketTracker packetTracker, PacketNumbers packetNumbers, TLSEngine tlsEngine, RateControl rateControl, FlowController flowController, StreamsController streamsController, Session.Listener listener, boolean client)
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
        this.rateControl = rateControl;
        installBean(rateControl);
        this.flowController = flowController;
        installBean(flowController);
        this.streamsController = streamsController;
        installBean(streamsController);
        this.client = client;
        this.parser = new PacketsParser(tlsEngine.getPacketProtector(), packetNumbers, new FramesParser());
        installBean(parser);
        this.flusher = new QuicFlusher(this);
        installBean(flusher);
        this.packetListener = new PacketProcessor();
        setDestinationConnectionId(BufferUtil.EMPTY_BYTES);
        setSourceConnectionId(tlsEngine.newRandomBytes(8));
        this.keepAliveTask = () -> false;

        biRemoteStreamMaxCount.set(quicConfiguration.getBidirectionalMaxStreams());
        uniRemoteStreamMaxCount.set(quicConfiguration.getUnidirectionalMaxStreams());
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

    public FlowController getFlowController()
    {
        return flowController;
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
    }

    public byte[] getSourceConnectionId()
    {
        return srcConnectionId;
    }

    public void setSourceConnectionId(byte[] srcConnectionId)
    {
        this.srcConnectionId = srcConnectionId;
        // RFC-9000 #17.3: received packets' dcid bytes are the local scid.
        parser.setDestinationConnectionId(srcConnectionId);
    }

    /// @return the negotiated application protocol
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
            keepAliveTask = getScheduler().schedule(() -> getExecutor().execute(this::sendKeepAlive), timeout * 2 / 3, MILLISECONDS);
        }
    }

    private void sendKeepAlive()
    {
        if (!isOpen())
            return;
        if (LOG.isDebugEnabled())
            LOG.debug("sending keepalive on {}", this);
        sendProbe(encryptionLevel);
        scheduleKeepAlive();
    }

    protected abstract void notIdle();

    public void onIdleTimeout(TimeoutException timeout)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("idle timeout expired on {}", this);

        // RFC-9000 #10: QUIC idle timeouts are fatal and cannot be ignored,
        // so we use the keep-alive mechanism to avoid that they fire, and
        // idle timeouts are initiated by upper layer protocols.

        // RFC-9000 #10.1: the idle timeout should close the
        // connection silently, but we send a ConnectionCloseFrame
        // to inform the other peer that the connection is broken.

        // Notify upwards.
        close(ErrorCode.NO_ERROR.code(), "idle_timeout", Callback.NOOP);
    }

    protected void setEncryptionLevel(EncryptionLevel encryptionLevel)
    {
        this.encryptionLevel = encryptionLevel;
    }

    public abstract int getUDPPayloadLength();

    public abstract boolean isRemoteAddressValidated();

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

        if (keepAliveTask != null)
            keepAliveTask.cancel();

        super.doStop();
    }

    public void bytesWritten(long bytesWritten)
    {
        sentBytes.addAndGet(bytesWritten);
        connection.bytesWritten(bytesWritten);
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
            case ZERO_RTT -> throw new UnsupportedOperationException();
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

    public long getBytesReceived()
    {
        return recvBytes.get();
    }

    public long getBytesSent()
    {
        return sentBytes.get();
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
        QuicStream stream = null;
        StreamsBlockedFrame stalled = null;
        try (var _ = lock.lock())
        {
            if (streams.containsKey(streamId))
                throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "duplicate_local_stream");

            if (closeState != CloseState.NOT_CLOSED)
                throw new QuicException(ErrorCode.NO_ERROR, "session_closed");

            boolean bidirectional = StreamId.isBidirectional(streamId);
            AtomicLong localStreamCount = bidirectional ? biLocalStreamCount : uniLocalStreamCount;
            long count = localStreamCount.get();
            long max = bidirectional ? getBidirectionalLocalStreamMaxCount() : getUnidirectionalLocalStreamMaxCount();
            if (max < 0 || count < max)
            {
                localStreamCount.incrementAndGet();
                stream = new QuicStream(this, streamId, true);
                streams.put(streamId, stream);
            }
            else
            {
                if (stallStreams(bidirectional))
                    stalled = new StreamsBlockedFrame(bidirectional, max);
            }
        }

        if (stalled != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("stalling streams on {}", this);
            sendFrames(EncryptionLevel.ONE_RTT, List.of(stalled), Callback.NOOP);
        }
        if (stream == null)
            throw new QuicException(ErrorCode.STREAM_LIMIT_ERROR, "local_stream_count_exceeded");

        // A local stream can only send up to what the remote stream wants to receive.
        Long maxSendData = stream.isBidirectional()
            ? remoteTransportParameters.get(INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE)
            : remoteTransportParameters.get(INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL);
        if (maxSendData != null)
            stream.updateSendMaxOffset(maxSendData);

        // A local stream can only receive up to what the local stream wants to receive.
        Long maxRecvData = stream.isBidirectional()
            ? localTransportParameters.get(INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL)
            : localTransportParameters.get(INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL);
        if (maxRecvData != null)
            stream.updateRecvMaxOffset(maxRecvData);

        flowController.onStreamCreated(stream);

        if (LOG.isDebugEnabled())
            LOG.debug("created local {} on {}", stream, this);

        return stream;
    }

    private QuicStream getOrCreateRemoteStream(Frame.WithStreamId frame)
    {
        long streamId = frame.streamId();
        boolean bidirectional = StreamId.isBidirectional(streamId);

        QuicStream stream;
        try (var _ = lock.lock())
        {
            stream = streams.get(streamId);
            if (stream != null)
                return stream;

            if (closeState != CloseState.NOT_CLOSED)
                return null;

            // A local stream id cannot create a new remote stream.
            // For example, a client can only receive a frame on a
            // client-initiated stream that the client created.
            if (StreamId.isLocal(streamId, client))
            {
                AtomicLong streamIds = StreamId.isBidirectional(streamId) ? biStreamIds : uniStreamIds;
                if (streamId > streamIds.get())
                    throw new QuicException(ErrorCode.STREAM_STATE_ERROR, "invalid_stream_id", frame.type());
                else
                    return null;
            }

            AtomicLong terminatedStreamId = bidirectional ? terminatedBiRemoteStream : terminatedUniRemoteStream;
            boolean terminated = streamId <= terminatedStreamId.get();
            if (terminated)
                return null;

            // Create a new stream, if allowed.
            AtomicLong remoteStreamCount = bidirectional ? biRemoteStreamCount : uniRemoteStreamCount;
            long count = remoteStreamCount.get();

            long max = bidirectional ? getBidirectionalRemoteStreamMaxCount() : getUnidirectionalRemoteStreamMaxCount();
            if (max > 0 && count >= max)
                throw new QuicException(ErrorCode.STREAM_LIMIT_ERROR, "remote_stream_count_exceeded", frame.type());

            remoteStreamCount.incrementAndGet();
            stream = new QuicStream(this, streamId, false);
            streams.put(streamId, stream);
        }

        // A remote stream can only send up to what the local stream wants to receive.
        Long maxSendData = bidirectional
            ? remoteTransportParameters.get(INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL)
            : remoteTransportParameters.get(INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL);
        if (maxSendData != null)
            stream.updateSendMaxOffset(maxSendData);

        // A remote stream can only receive up to what the remote stream wants to receive.
        Long maxRecvData = stream.isBidirectional()
            ? localTransportParameters.get(INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE)
            : localTransportParameters.get(INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL);
        if (maxRecvData != null)
            stream.updateRecvMaxOffset(maxRecvData);

        flowController.onStreamCreated(stream);

        streamsController.onStreamCreated(stream);

        if (LOG.isDebugEnabled())
            LOG.debug("created remote {} for {} on {}", stream, frame, this);

        Stream.Listener listener = notifyNewStream(frame);
        stream.setListener(listener);
        stream.onNewStream(frame);

        return stream;
    }

    @Override
    public QuicStream getStream(long streamId)
    {
        try (var _ = lock.lock())
        {
            return streams.get(streamId);
        }
    }

    @Override
    public Collection<Stream> getStreams()
    {
        try (var _ = lock.lock())
        {
            return List.copyOf(streams.values());
        }
    }

    void remove(QuicStream stream)
    {
        long streamId = stream.getId();

        boolean removed;
        try (var _ = lock.lock())
        {
            removed = streams.remove(streamId) != null;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("removed {} {} from {}", removed, stream, this);

        if (!removed)
            return;

        AtomicLong terminated = stream.isBidirectional()
            ? stream.isLocal() ? terminatedBiLocalStream : terminatedBiRemoteStream
            : stream.isLocal() ? terminatedUniLocalStream : terminatedUniRemoteStream;
        Atomics.updateMax(terminated, streamId);

        stream.notifyTerminated();

        flowController.onStreamTerminated(stream);

        if (!stream.isLocal())
            streamsController.onStreamTerminated(stream);
    }

    @Override
    public void maxStreams(long maxStreams, boolean bidirectional, Callback callback)
    {
        if (maxStreams > StreamId.MAX_COUNT)
        {
            callback.failed(new QuicException(ErrorCode.STREAM_STATE_ERROR, "invalid_max_streams", bidirectional ? 0x12 : 0x13));
            return;
        }

        AtomicLong maxCount = bidirectional ? biRemoteStreamMaxCount : uniRemoteStreamMaxCount;
        if (Atomics.updateMax(maxCount, maxStreams))
            sendFrames(EncryptionLevel.ONE_RTT, List.of(new MaxStreamsFrame(maxStreams, bidirectional)), callback);
        else
            callback.succeeded();
    }

    @Override
    public void ping(Callback callback)
    {
        List<Frame> frames = List.of(PingFrame.INSTANCE);
        sendFrames(EncryptionLevel.ONE_RTT, frames, callback);
    }

    @Override
    public void maxData(long maxData, Callback callback)
    {
        if (maxData > VarLenInt.MAX_VALUE)
        {
            callback.failed(new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_max_data", 0x10));
            return;
        }
        if (updateRecvMaxOffset(maxData))
            sendFrames(EncryptionLevel.ONE_RTT, List.of(new MaxDataFrame(maxData)), callback);
        else
            callback.succeeded();
    }

    public void data(QuicStream stream, StreamFrame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sending data {} on {} on {}", frame, stream, this);
        flusher.sendFrames(stream, List.of(frame), callback);
    }

    void maxData(QuicStream stream, StreamMaxDataFrame frame, Callback callback)
    {
        long max = frame.maxData();
        if (max > VarLenInt.MAX_VALUE)
        {
            callback.failed(new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_stream_max_data", frame.type()));
            return;
        }
        if (stream.updateRecvMaxOffset(max))
            sendFrames(stream, List.of(frame), callback);
        else
            callback.succeeded();
    }

    public void reset(QuicStream stream, ResetStreamFrame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("resetting {} for {} on {}", frame, stream, this);
        checkRateControl(frame);
        sendFrames(stream, List.of(frame), callback);
    }

    void stopSending(QuicStream stream, StopSendingFrame frame, Callback callback)
    {
        sendFrames(stream, List.of(frame), callback);
    }

    void sendProbe(EncryptionLevel encryptionLevel)
    {
        if (isOpen())
            flusher.sendProbe(encryptionLevel);
    }

    void sendFrames(EncryptionLevel encryptionLevel, List<Frame> frames, Callback callback)
    {
        if (isOpen())
            flusher.sendFrames(encryptionLevel, frames, callback);
        else
            callback.failed(new ClosedChannelException());
    }

    void sendFrames(QuicStream stream, List<Frame> frames, Callback callback)
    {
        if (isOpen())
            flusher.sendFrames(stream, frames, callback);
        else
            callback.failed(new ClosedChannelException());
    }

    @Override
    public void disconnect(long appError, String reason, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting {}/{} on {}", appError, reason, this);

        disconnect(new ConnectionCloseFrame(appError, reason), failure, callback);
    }

    /// Disconnects this session with the given QUIC error code, reason and frame type.
    ///
    /// This method eventually sends a [ConnectionCloseFrame] of type `0x1C`.
    ///
    /// @param quicError the QUIC error code
    /// @param reason the error reason
    /// @param causeFrameType the frame type that caused the error
    /// @param failure the error
    /// @param callback the [Callback] to notify when the termination is complete
    public void disconnect(long quicError, String reason, long causeFrameType, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting {}/{}/{} on {}", quicError, reason, causeFrameType, this);

        disconnect(new ConnectionCloseFrame(quicError, reason, causeFrameType), failure, callback);
    }

    private void disconnect(ConnectionCloseFrame frame, Throwable failure, Callback callback)
    {
        if (updateToClosing())
        {
            // Remember the close frame to send it again if necessary.
            closeFrame.compareAndSet(null, frame);
            if (failure == null)
                failure = new QuicException(ErrorCode.from(frame.errorCode()), frame.reason(), frame.causeFrameType());
            disconnect(failure, callback);
        }
        else
        {
            callback.succeeded();
        }
    }

    private boolean updateToClosing()
    {
        boolean disconnect;
        try (var _ = lock.lock())
        {
            disconnect = switch (closeState)
            {
                case NOT_CLOSED ->
                {
                    closeState = CloseState.CLOSING;

                    // RFC-9000 #10.2: closing and draining states
                    // should persist for 3 times the current PTO.
                    RTTData rttData = getPacketTracker().getRTTData();
                    long pto = rttData.smoothedRTT() + 4 * rttData.variationRTT();
                    getScheduler().schedule(this::terminate, 3 * pto, NANOSECONDS);

                    yield true;
                }
                case CLOSING ->
                {
                    // The closing was already initiated.
                    yield false;
                }
                case DRAINING, CLOSED ->
                {
                    // RFC-9002 #10.2.2: an endpoint in the
                    // draining state must not send any packets.
                    yield false;
                }
            };
        }
        return disconnect;
    }

    private void disconnect(Throwable failure, Callback callback)
    {
        // Tear down all the streams.
        List<QuicStream> streamsToFail;
        try (var _ = lock.lock())
        {
            streamsToFail = List.copyOf(streams.values());
        }
        streamsToFail.stream()
            .map(stream -> stream.processFailure(failure))
            .forEach(t -> offerTask(t, false));

        ConnectionCloseFrame frame = closeFrame.get();
        flusher.sendClose(encryptionLevel, frame, Callback.from(callback, () -> disconnectComplete(frame)));
    }

    private void disconnectComplete(ConnectionCloseFrame frame)
    {
        emitDisconnect(frame);
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

    long getMaxStreams(boolean bidirectional)
    {
        TransportParameters.Id<Long> param = bidirectional
            ? INITIAL_MAX_STREAMS_BIDIRECTIONAL
            : INITIAL_MAX_STREAMS_UNIDIRECTIONAL;
        Long maxStreams = localTransportParameters.get(param);
        return maxStreams == null ? 0 : maxStreams;
    }

    @Override
    public long getBidirectionalLocalStreamMaxCount()
    {
        return biLocalStreamMaxCount.get();
    }

    public long getUnidirectionalLocalStreamMaxCount()
    {
        return uniLocalStreamMaxCount.get();
    }

    /// @return the cumulative number of bidirectional remote streams that were opened
    public long getBidirectionalRemoteStreamCount()
    {
        return biRemoteStreamCount.get();
    }

    /// @return the max cumulative number of bidirectional remote streams, as advertised by the remote peer
    public long getBidirectionalRemoteStreamMaxCount()
    {
        return biRemoteStreamMaxCount.get();
    }

    /// @return the cumulative number of unidirectional remote streams that were opened
    public long getUnidirectionalRemoteStreamCount()
    {
        return uniRemoteStreamCount.get();
    }

    /// @return the max cumulative number of unidirectional remote streams, as advertised by the remote peer
    public long getUnidirectionalRemoteStreamMaxCount()
    {
        return uniRemoteStreamMaxCount.get();
    }

    @Override
    public X509Certificate[] getPeerCertificates()
    {
        return new X509Certificate[0];
    }

    @Override
    public void offerTask(Runnable task, boolean dispatch)
    {
        connection.offerTask(Invocable.wrap(task), dispatch);
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
                    disconnect(quicException.getErrorCode().code(), quicException.getMessage(), 0x00, quicException, Callback.NOOP);
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
        boolean process;
        try (var _ = lock.lock())
        {
            process = closeState == CloseState.NOT_CLOSED;
            if (closeState == CloseState.DRAINING || closeState == CloseState.CLOSED)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("dropping incoming packet {} on {}", packet, this);
                return;
            }
        }

        if (process)
        {
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

                recvBytes.addAndGet(packet.length());
                packetNumbers.onPacketReceived(packet);

                List<Invocable.Task> tasks = processPacket(packet);
                for (Invocable.Task task : tasks)
                {
                    offerTask(task, false);
                }
            }
            else
            {
                // RFC-9000 #7.2: the packet must be discarded
                // if the packet dcid does not match.
                if (LOG.isDebugEnabled())
                    LOG.debug("packet {} does not match connection id on {}", packet, this);
            }
        }
        else
        {
            if (packet instanceof Packet.WithFrames pwf)
            {
                if (pwf.frames().stream().anyMatch(f -> f instanceof ConnectionCloseFrame))
                {
                    updateToDraining();
                    return;
                }
            }

            // RFC-9000 #10.2.1: send a ConnectionCloseFrame in
            // response to any packet received in CLOSING state.
            // Use a power-of-2 backoff to avoid sending too many
            // frames in reply to those sent by the remote peer.
            if (Long.bitCount(++closeBackoff) == 1)
            {
                ConnectionCloseFrame frame = closeFrame.get();
                Throwable failure = new QuicException(ErrorCode.from(frame.errorCode()), frame.reason(), frame.causeFrameType());
                disconnect(failure, Callback.NOOP);
            }
        }
    }

    protected List<Invocable.Task> processPacket(Packet packet)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("processing {} on {}", packet, this);

            // The packet was fully decrypted and parsed, ack it now.
            // Processing of frames by a different layer (such as the
            // TLS layer or the application layer) is independent of
            // acknowledgments at the transport layer.
            // RetryPacket and VersionNegotiationPacket, handled by
            // clients, must not be acked.
            acknowledge(packet);

            return switch (packet)
            {
                case InitialPacket initialPacket ->
                {
                    yield processFrames(initialPacket);
                }
                case HandshakePacket handshakePacket ->
                {
                    discardEncryptionLevel(EncryptionLevel.INITIAL);
                    setEncryptionLevel(EncryptionLevel.HANDSHAKE);
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
            fail(x, false);
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
                processFrame(packet, frame);
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

    private Invocable.Task processStreamFrames(List<Frame.WithStreamId> frames)
    {
        Frame.WithStreamId first = frames.getFirst();
        QuicStream stream = getOrCreateRemoteStream(first);
        if (stream == null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("dropping frame {} for terminated stream #{} on {}", first, first.streamId(), this);
            checkRateControl(first);
            return null;
        }

        // Perform some initial processing of the frames.

        long streamOffset = 0;
        for (Frame.WithStreamId frame : frames)
        {
            streamOffset = Math.max(streamOffset, switch (frame)
            {
                case ResetStreamFrame resetStreamFrame ->
                {
                    // RFC-9000 #19.4: local unidirectional stream receiving a reset produces a connection error.
                    if (stream.isLocal() && !stream.isBidirectional())
                        throw new QuicException(ErrorCode.STREAM_STATE_ERROR, "local_unidirectional_stream_reset", frame.type());
                    yield resetStreamFrame.finalSize();
                }
                case StreamFrame streamFrame -> streamFrame.offset() + streamFrame.length();
                case StreamMaxDataFrame streamMaxDataFrame ->
                {
                    flusher.processMaxData(stream, streamMaxDataFrame);
                    yield 0;
                }
                default -> 0;
            });
        }

        // Handle flow control.
        if (streamOffset > 0)
        {
            if (streamOffset > stream.getRecvMaxOffset())
                throw new QuicException(ErrorCode.FLOW_CONTROL_ERROR, "stream_max_data_exceeded", 0x08);
            long delta = streamOffset - stream.getRecvOffset();
            stream.updateRecvOffset(streamOffset);
            if (delta > 0)
            {
                long sessionOffset = getRecvOffset() + delta;
                if (sessionOffset > getRecvMaxOffset())
                    throw new QuicException(ErrorCode.FLOW_CONTROL_ERROR, "session_max_data_exceeded", 0x08);
                updateRecvOffset(sessionOffset);
            }
            flowController.onDataReceived(stream);
        }

        return stream.processFrames(frames);
    }

    protected void processFrame(Packet.WithFrames packet, Frame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} in {} on {}", frame, packet, this);
        switch (frame)
        {
            case AckFrame ackFrame ->
            {
                EncryptionLevel encryptionLevel = EncryptionLevel.from(packet);
                if (ackFrame.largestAcknowledged() <= getPacketNumbers().largestAcknowledged(encryptionLevel))
                    checkRateControl(frame);
                processAckFrame(encryptionLevel, ackFrame);
            }
            case ConnectionCloseFrame connectionCloseFrame -> processConnectionCloseFrame(connectionCloseFrame);
            case CryptoFrame cryptoFrame ->
            {
                if (cryptoFrame.length() == 0)
                    checkRateControl(frame);
                EncryptionLevel encryptionLevel = EncryptionLevel.from(packet);
                cryptoStreams.computeIfAbsent(encryptionLevel, e -> new FrameStream.Crypto(e, this::processCryptoFrame)).offer(cryptoFrame);
            }
            case DataBlockedFrame dataBlockedFrame ->
            {
                checkRateControl(frame);
                notifyDataBlocked(dataBlockedFrame);
            }
            case MaxDataFrame maxDataFrame ->
            {
                if (maxDataFrame.maxData() <= getSendMaxOffset())
                    checkRateControl(frame);
                // Serialize processing of maxData frames through the flusher.
                flusher.processMaxData(maxDataFrame);
                notifyMaxData(maxDataFrame);
            }
            case MaxStreamsFrame maxStreamsFrame ->
            {
                if (updateMaxStreams(maxStreamsFrame.isBidirectional(), maxStreamsFrame.maxStreams()))
                    notifyMaxStreams(maxStreamsFrame);
                else
                    checkRateControl(frame);
            }
            case NewConnectionIdFrame newConnectionIdFrame ->
            {
                checkRateControl(frame);
                // TODO
            }
            case PathChallengeFrame pathChallengeFrame ->
            {
                checkRateControl(frame);
                // TODO
            }
            case PathResponseFrame pathResponseFrame ->
            {
                checkRateControl(frame);
                // TODO
            }
            case PingFrame _ ->
            {
                checkRateControl(frame);
                notifyPing();
            }
            case RetireConnectionIdFrame retireConnectionIdFrame ->
            {
                checkRateControl(frame);
                // TODO
            }
            case StreamsBlockedFrame streamsBlockedFrame ->
            {
                checkRateControl(frame);
                notifyStreamsBlocked(streamsBlockedFrame);
            }
            default -> throw new UnsupportedOperationException();
        }
    }

    void checkRateControl(Frame frame)
    {
        if (!rateControl.onEvent(frame))
            throw new QuicException(ErrorCode.PROTOCOL_VIOLATION_ERROR, "invalid_frame_rate", frame.type());
    }

    private void processAckFrame(EncryptionLevel encryptionLevel, AckFrame frame)
    {
        packetNumbers.onAckFrameReceived(encryptionLevel, frame);
        flusher.onAckFrameReceived(encryptionLevel, frame);
    }

    private void processConnectionCloseFrame(ConnectionCloseFrame frame)
    {
        if (!updateToDraining())
            return;

        // Tear down all the streams.
        List<QuicStream> streamsToFail;
        try (var _ = lock.lock())
        {
            streamsToFail = List.copyOf(streams.values());
        }
        Throwable failure = new QuicException(ErrorCode.from(frame.errorCode()), frame.reason(), frame.causeFrameType());
        streamsToFail.stream()
            .map(stream -> stream.processFailure(failure))
            .forEach(t -> offerTask(t, false));

        getTLSEngine().tryFail(failure);

        notifyConnectionClose(frame);

        // RFC-9000 #10.2: in the draining state we must not send
        // any frames, so just perform the after-complete actions.
        disconnectComplete(frame);
    }

    private boolean updateToDraining()
    {
        try (var _ = lock.lock())
        {
            return switch (closeState)
            {
                case NOT_CLOSED ->
                {
                    closeState = CloseState.DRAINING;

                    // RFC-9000 #10.2: closing and draining states
                    // should persist for 3 times the current PTO.
                    RTTData rttData = getPacketTracker().getRTTData();
                    long pto = rttData.smoothedRTT() + 4 * rttData.variationRTT();
                    getScheduler().schedule(this::terminate, 3 * pto, NANOSECONDS);

                    yield true;
                }
                case CLOSING ->
                {
                    closeState = CloseState.DRAINING;
                    yield false;
                }
                case DRAINING, CLOSED -> false;
            };
        }
    }

    protected void discardEncryptionLevel(EncryptionLevel encryptionLevel)
    {
        flusher.onScheduledTask(() ->
        {
            getTLSEngine().getPacketProtector().discardKeys(encryptionLevel);
            flusher.discard(encryptionLevel);
            getPacketTracker().discard(encryptionLevel);
        });
    }

    long getMaxData()
    {
        return localTransportParameters.get(INITIAL_MAX_DATA);
    }

    long getMaxData(QuicStream stream)
    {
        if (stream.isBidirectional())
        {
            return stream.isLocal()
                ? localTransportParameters.get(INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL)
                : localTransportParameters.get(INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE);
        }
        else
        {
            return localTransportParameters.get(INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL);
        }
    }

    /// @return the received data offset for this session
    public long getRecvOffset()
    {
        return recvOffset.get();
    }

    /// @return the received max data offset for this session
    public long getRecvMaxOffset()
    {
        return recvMaxOffset.get();
    }

    /// Updates the received data offset for this session.
    ///
    /// This method is called when [StreamFrame]s are received.
    private void updateRecvOffset(long offset)
    {
        Atomics.updateMax(recvOffset, offset);
    }

    /// Updates the received max data offset for this session.
    ///
    /// This method is called initially when sending
    /// the [TransportParameters.Ids#INITIAL_MAX_DATA],
    /// and later when sending [MaxDataFrame]s.
    private boolean updateRecvMaxOffset(long offset)
    {
        return Atomics.updateMax(recvMaxOffset, offset);
    }

    /// The number of bytes that it is possible to send.
    ///
    /// This value is `sentMaxOffset - sentOffset`, and it is used
    /// during generation to limit the number of bytes that it
    /// is possible to send.
    ///
    /// @return the number of bytes that it is possible to send
    public long getSendWindow()
    {
        return sendMaxOffset.get() - getSentOffset();
    }

    /// The sent data offset for this session.
    ///
    /// This value is tracked to avoid exceeding the limit set by the
    /// remote peer for the amount of bytes it is willing to receive.
    ///
    /// @return the sent data offset for this session
    public long getSentOffset()
    {
        return sentOffset.get();
    }

    /// Updates the sent data offset for this session.
    ///
    /// This method is called when [StreamFrame]s are generated.
    public void updateSentOffset(long offset)
    {
        Atomics.updateMax(sentOffset, offset);
    }

    /// @return the send max data offset for this session
    public long getSendMaxOffset()
    {
        return sendMaxOffset.get();
    }

    /// Updates the send max data offset for this session.
    ///
    /// This method is called initially when receiving
    /// the [TransportParameters.Ids#INITIAL_MAX_DATA],
    /// and later when receiving [MaxDataFrame]s.
    public void updateSendMaxOffset(long newValue)
    {
        if (Atomics.updateMax(sendMaxOffset, newValue))
            flowControlStalled.set(false);
    }

    public boolean stallFlowControl()
    {
        return flowControlStalled.compareAndSet(false, true);
    }

    private boolean updateMaxStreams(boolean bidirectional, long newValue)
    {
        AtomicLong maxStreams = bidirectional ? biLocalStreamMaxCount : uniLocalStreamMaxCount;
        if (Atomics.updateMax(maxStreams, newValue))
        {
            if (bidirectional)
                biLocalStreamsStalled = false;
            else
                uniLocalStreamsStalled = false;
            return true;
        }
        return false;
    }

    private boolean stallStreams(boolean bidirectional)
    {
        boolean stalled = bidirectional ? biLocalStreamsStalled : uniLocalStreamsStalled;
        if (bidirectional)
            biLocalStreamsStalled = true;
        else
            uniLocalStreamsStalled = true;
        return !stalled;
    }

    private void processCryptoFrame(Frame.WithOffset frame)
    {
        try
        {
            CryptoFrame cryptoFrame = (CryptoFrame)frame;
            while (cryptoFrame.remaining() > 0)
            {
                Message message = cryptoFrame.map(data -> getTLSEngine().getMessagesParser().parse(data));
                if (LOG.isDebugEnabled())
                    LOG.debug("parsed {} on {}", message, this);
                if (message == null)
                    return;
                processMessage(message);
            }
        }
        catch (Throwable x)
        {
            fail(x, false);
        }
    }

    protected abstract void processMessage(Message message);

    protected void processTransportParameters(TransportParameters transportParameters)
    {
        configure(transportParameters, false);
        notifyTransportParameters(transportParameters);
    }

    protected void configure(TransportParameters parameters, boolean local)
    {
        if (!local)
        {
            // RFC-9000 #7.3: iscid must always be present.
            byte[] iscid = parameters.get(INITIAL_SOURCE_CONNECTION_ID);
            if (iscid == null || !Arrays.equals(iscid, getDestinationConnectionId()))
                throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_0x%02X".formatted(INITIAL_SOURCE_CONNECTION_ID.id()));

            // RFC-9000 #18.2.
            Long maxUDP = parameters.get(MAX_UDP_PAYLOAD_SIZE);
            if (maxUDP != null && maxUDP < 1200)
                throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_0x%02X".formatted(MAX_UDP_PAYLOAD_SIZE.id()));

            // RFC-9000 #18.2.
            Long ackDelayExp = parameters.get(ACK_DELAY_EXPONENT);
            if (ackDelayExp != null && ackDelayExp > 20)
                throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_0x%02X".formatted(ACK_DELAY_EXPONENT.id()));

            // RFC-9000 #18.2.
            Long maxAckDelay = parameters.get(MAX_ACK_DELAY);
            if (maxAckDelay != null && maxAckDelay >= (1 << 14))
                throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_0x%02X".formatted(MAX_ACK_DELAY.id()));

            // RFC-9000 #18.2.
            Long activeIds = parameters.get(ACTIVE_CONNECTION_ID_LIMIT);
            if (activeIds != null && activeIds < 2)
                throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_0x%02X".formatted(ACTIVE_CONNECTION_ID_LIMIT.id()));

            // RFC-9000 #18.2.
            Long biMaxStreams = parameters.get(INITIAL_MAX_STREAMS_BIDIRECTIONAL);
            if (biMaxStreams != null && biMaxStreams > StreamId.MAX_COUNT)
                throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_0x%02X".formatted(INITIAL_MAX_STREAMS_BIDIRECTIONAL.id()));

            // RFC-9000 #18.2.
            Long uniMaxStreams = parameters.get(INITIAL_MAX_STREAMS_UNIDIRECTIONAL);
            if (uniMaxStreams != null && uniMaxStreams > StreamId.MAX_COUNT)
                throw new QuicException(ErrorCode.TRANSPORT_PARAMETER_ERROR, "invalid_transport_parameter_0x%02X".formatted(INITIAL_MAX_STREAMS_UNIDIRECTIONAL.id()));
        }

        if (local)
            localTransportParameters = parameters;
        else
            remoteTransportParameters = parameters;

        Long idleTimeout = parameters.get(MAX_IDLE_TIMEOUT);
        if (idleTimeout != null)
        {
            if (local)
            {
                setIdleTimeout(idleTimeout);
            }
            else
            {
                // RFC-9000 #10.1: the idle timeout is the minimum of the two advertised values.
                if (idleTimeout > 0)
                {
                    long localIdleTimeout = getIdleTimeout();
                    if (localIdleTimeout > 0)
                        setIdleTimeout(Math.min(localIdleTimeout, idleTimeout));
                    else
                        setIdleTimeout(idleTimeout);
                }
            }
        }

        Long maxData = parameters.get(INITIAL_MAX_DATA);
        if (maxData != null)
        {
            if (local)
                updateRecvMaxOffset(maxData);
            else
                updateSendMaxOffset(maxData);
        }

        Long ackMaxDelay = parameters.get(MAX_ACK_DELAY);
        if (ackMaxDelay != null)
        {
            if (!local)
                packetTracker.setAcknowledgmentMaxDelay(ackMaxDelay);
        }
        Long ackDelayExponent = parameters.get(ACK_DELAY_EXPONENT);
        if (ackDelayExponent != null)
        {
            if (!local)
                packetTracker.setAcknowledgmentDelayExponent(ackDelayExponent);
        }

        Long uniMaxStreams = parameters.get(INITIAL_MAX_STREAMS_UNIDIRECTIONAL);
        if (uniMaxStreams != null)
        {
            if (local)
                uniRemoteStreamMaxCount.set(uniMaxStreams);
            else
                uniLocalStreamMaxCount.set(uniMaxStreams);
        }
        Long biMaxStreams = parameters.get(INITIAL_MAX_STREAMS_BIDIRECTIONAL);
        if (biMaxStreams != null)
        {
            if (local)
                biRemoteStreamMaxCount.set(biMaxStreams);
            else
                biLocalStreamMaxCount.set(biMaxStreams);
        }
    }

    private void acknowledge(Packet packet)
    {
        if (packet instanceof Packet.WithFrames p && p.requiresAcknowledgement())
            flusher.sendAcknowledgment(p, Callback.NOOP);
    }

    /// Implicitly acknowledge the given packet number, as if an [AckFrame]
    /// for that packet number has been received.
    protected void onAcknowledge(EncryptionLevel encryptionLevel, long packetNumber)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("implicit acknowledge for {}[{}] on {}", encryptionLevel, packetNumber, this);
        flusher.onAckFrameReceived(encryptionLevel, new AckFrame(packetNumber, 0, 0, List.of()));
    }

    public void packet(Packet packet, Callback callback)
    {
        flusher.sendPacket(packet, callback);
    }

    public PacketListener getPacketListener()
    {
        return packetListener;
    }

    public void setPacketListener(PacketListener listener)
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
                    case ResetStreamFrame resetStreamFrame ->
                    {
                        // TODO: reset() sets the stream to locally closed after the send.
                        //  However, we should think about making it so after we received
                        //  and ack for it?
                        QuicStream stream = getStream(resetStreamFrame.streamId());
                        if (stream != null && !stream.isLocallyClosed())
                            stream.reset(resetStreamFrame.applicationErrorCode(), Callback.NOOP);
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
                            stream.stopSending(stopSendingFrame.applicationErrorCode(), Callback.NOOP);
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
                            data(stream, streamFrame, Callback.NOOP);
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
                        //  see RFC-9000 #13.3
                    }
                    case StreamsBlockedFrame streamsBlockedFrame ->
                    {
                        // TODO: only resend if still blocked.
                    }
                }
            }
        }
    }

    public void fail(Throwable x, boolean dispatch)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("failure on {}", this, x);

        // Serialize failure and disconnect events.
        offerTask(new ReadyTask(NON_BLOCKING, () ->
        {
            // TODO: must decide whether this needs to notify upwards,
            //  which may require a Callback like close(), and then disconnect.
            //  The failure notified upwards should just dispose things
            //  without attempting network communication (differently from close).

            notifyFailure(x);

            long errorCode;
            long causeFrameType;
            switch (x)
            {
                case TLSException tls ->
                {
                    // RFC-9000 #20.1: convert TLS alerts into CRYPTO_ERRORs.
                    errorCode = ErrorCode.CRYPTO_ERROR.code() + tls.getAlert().code();
                    causeFrameType = 0x06;
                }
                case QuicException quic ->
                {
                    errorCode = quic.getErrorCode().code();
                    causeFrameType = quic.getFrameType();
                }
                default ->
                {
                    errorCode = ErrorCode.INTERNAL_ERROR.code();
                    causeFrameType = 0x00;
                }
            }
            disconnect(errorCode, x.getMessage(), causeFrameType, x, Callback.NOOP);
        }), dispatch);
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
        try (var _ = lock.tryLock())
        {
            String held = lock.isHeldByCurrentThread() ? "" : "?";
            return "%s[%s:%s,dcid=%s,scid=%s,streams=%d]".formatted(
                super.toString(), held, closeState,
                StringUtil.toHexString(getDestinationConnectionId()),
                StringUtil.toHexString(getSourceConnectionId()),
                streams.size());
        }
    }

    /// Listener for incoming [Packet]s.
    ///
    /// [QuicSession]'s default `PacketListener` processes incoming packets,
    /// and may be wrapped by applications in this way:
    ///
    /// ```java
    /// quicSession.setPacketListener(new PacketListener.Wrapper(quicSession.getPacketListener())
    /// {
    ///     @Override
    ///     public void onIncomingPacket(Session session, Packet packet)
    ///     {
    ///         ...
    ///     }
    /// });
    /// ```
    ///
    /// The incoming packet is processed only if the wrapping `PacketListener` eventually
    /// delegates the incoming packet event to the default `PacketListener`.
    /// Not delegating the incoming packet event the default `PacketListener` is equivalent
    /// to losing the packet, which can be used to simulate network packet loss.
    public interface PacketListener
    {
        default void onIncomingPacket(Session session, Packet packet)
        {
        }

        class Wrapper implements PacketListener
        {
            private final PacketListener wrapped;

            public Wrapper(PacketListener wrapped)
            {
                this.wrapped = wrapped;
            }

            public PacketListener getWrapped()
            {
                return wrapped;
            }

            @Override
            public void onIncomingPacket(Session session, Packet packet)
            {
                getWrapped().onIncomingPacket(session, packet);
            }
        }
    }

    private class PacketProcessor implements PacketListener
    {
        @Override
        public void onIncomingPacket(Session session, Packet packet)
        {
            process(packet);
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
