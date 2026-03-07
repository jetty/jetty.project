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

package org.eclipse.jetty.quic.common.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.quic.common.frames.FramesGenerator;
import org.eclipse.jetty.quic.common.internal.packets.PacketsGenerator;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.quic.common.packets.VersionNegotiationPacket;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicFlusher extends IteratingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final PacketFlusher packetFlusher = new PacketFlusher(this);
    private final CryptoFlusher initialFlusher = new CryptoFlusher(this, EncryptionLevel.INITIAL);
    private final CryptoFlusher handshakeFlusher = new CryptoFlusher(this, EncryptionLevel.HANDSHAKE);
    private final OneRTTFlusher oneRTTFlusher = new OneRTTFlusher(this);
    private final Deque<AckEntry> ackEntries = new ArrayDeque<>();
    private final QuicSession session;
    private final FramesGenerator framesGenerator;
    private final PacketsGenerator packetsGenerator;
    private final RetainableByteBuffer.Mutable plaintextBuffer;
    private final RetainableByteBuffer.Mutable encryptedBuffer;
    private long pacingDelayThreshold = TimeUnit.MILLISECONDS.toNanos(1);
    private Callback flusher;

    public QuicFlusher(QuicSession session)
    {
        this.session = session;
        ByteBufferPool byteBufferPool = session.getByteBufferPool();
        this.framesGenerator = new FramesGenerator(byteBufferPool);
        this.packetsGenerator = new PacketsGenerator(session.getPacketNumbers(), framesGenerator, session.getTLSEngine().getPacketProtector());
        boolean direct = session.getQuicConfiguration().isUseOutputDirectByteBuffers();
        this.plaintextBuffer = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, direct, -1, 0, 0);
        this.encryptedBuffer = new RetainableByteBuffer.DynamicCapacity(byteBufferPool, direct, -1, 0, 0);
    }

    public QuicSession getQuicSession()
    {
        return session;
    }

    public FramesGenerator getFramesGenerator()
    {
        return framesGenerator;
    }

    public PacketsGenerator getPacketsGenerator()
    {
        return packetsGenerator;
    }

    RetainableByteBuffer.Mutable getPlaintextBuffer()
    {
        return plaintextBuffer;
    }

    RetainableByteBuffer.Mutable getEncryptedBuffer()
    {
        return encryptedBuffer;
    }

    public long getPacingDelayThreshold()
    {
        return pacingDelayThreshold;
    }

    public void setPacingDelayThreshold(long pacingDelayThreshold)
    {
        this.pacingDelayThreshold = pacingDelayThreshold;
    }

    /// Sends the given packet.
    ///
    /// This method should be called only for [RetryPacket] and [VersionNegotiationPacket],
    /// since packets that carry frames should call [#offer(EncryptionLevel, List, Callback)].
    ///
    /// @param packet the [Packet] to send
    /// @param callback the [Callback] to notify when the send is complete
    public boolean offer(Packet packet, Callback callback)
    {
        return packetFlusher.offer(packet, callback);
    }

    /// Sends the given list of session frames at the given [EncryptionLevel].
    ///
    /// @param encryptionLevel the encryption level to use for the send
    /// @param frames the list of frames to send
    /// @param callback the [Callback] to notify when the send is complete
    public boolean offer(EncryptionLevel encryptionLevel, List<Frame> frames, Callback callback)
    {
        return switch (encryptionLevel)
        {
            case INITIAL -> initialFlusher.offer(frames, callback);
            case HANDSHAKE -> handshakeFlusher.offer(frames, callback);
            case ONE_RTT -> oneRTTFlusher.offer(null, frames, callback);
            default -> throw new UnsupportedOperationException();
        };
    }

    /// Sends the given list of stream frames at [EncryptionLevel#ONE_RTT].
    ///
    /// @param stream the stream
    /// @param frames the list of frames to send
    /// @param callback the [Callback] to notify when the send is complete
    public boolean offer(QuicStream stream, List<Frame> frames, Callback callback)
    {
        return oneRTTFlusher.offer(stream, frames, callback);
    }

    /// Processes the [MaxDataFrame] to update the session send max data.
    ///
    /// @param frame the [MaxDataFrame] with the session send max data update
    public boolean offer(MaxDataFrame frame)
    {
        return oneRTTFlusher.offer(null, frame.maxData());
    }

    /// Processes the [StreamMaxDataFrame] for the given stream to update the stream send max data.
    ///
    /// @param stream the stream
    /// @param frame the [StreamMaxDataFrame] with the stream send max data update
    public boolean offer(QuicStream stream, StreamMaxDataFrame frame)
    {
        return oneRTTFlusher.offer(stream, frame.maxData());
    }

    // TODO: Processes the [AckFrame] to update
    //  congestion-control, pacing, reliability data structures.
    public boolean offer(EncryptionLevel encryptionLevel, AckFrame frame)
    {
        try (var _ = lock.lock())
        {
            ackEntries.offer(new AckEntry(encryptionLevel, frame));
            return true;
        }
    }

    @Override
    protected Action process() throws Throwable
    {
        List<AckEntry> acks;
        try (var _ = lock.lock())
        {
            acks = new ArrayList<>(ackEntries);
            ackEntries.clear();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("processing {} ack frames on {}", acks.size(), this);

        for (AckEntry entry : acks)
        {
            getQuicSession().processAckFrame(entry.encryptionLevel(), entry.frame());
        }

        long pacingDelay = getQuicSession().getCongestionController().getPacingDelay();

        if (LOG.isDebugEnabled())
            LOG.debug("pacing delay {} micros on {}", TimeUnit.NANOSECONDS.toMicros(pacingDelay), this);

        if (pacingDelay > getPacingDelayThreshold())
        {
            // TODO: schedule wakeup.
            return Action.IDLE;
        }

        if (oneRTTFlusher.process())
        {
            flusher = oneRTTFlusher;
            return Action.SCHEDULED;
        }

        if (handshakeFlusher.process())
        {
            flusher = handshakeFlusher;
            return Action.SCHEDULED;
        }

        if (initialFlusher.process())
        {
            flusher = initialFlusher;
            return Action.SCHEDULED;
        }

        if (packetFlusher.process())
        {
            flusher = packetFlusher;
            return Action.SCHEDULED;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("no entries to flush on {}", this);

        return Action.IDLE;
    }

    @Override
    protected void onSuccess()
    {
        encryptedBuffer.clear();
        plaintextBuffer.clear();
        flusher.succeeded();
        flusher = null;
    }

    @Override
    protected void onCompleteFailure(Throwable cause)
    {
        // TODO: release the buffers here, this instance is thrown away.
        //  Also, notify the session to disconnect()?
        encryptedBuffer.clear();
        plaintextBuffer.clear();
        if (flusher != null)
            flusher.failed(cause);
        flusher = null;
    }

    public void resetCrypto()
    {
        initialFlusher.resetCrypto();
    }

    sealed interface Entry extends Callback permits PacketFlusher.PacketEntry, OneRTTFlusher.MaxDataEntry, CryptoFlusher.FramesEntry
    {
        Callback callback();

        @Override
        default void succeeded()
        {
            callback().succeeded();
        }

        @Override
        default void failed(Throwable x)
        {
            callback().failed(x);
        }

        @Override
        default InvocationType getInvocationType()
        {
            return callback().getInvocationType();
        }
    }

    private record AckEntry(EncryptionLevel encryptionLevel, AckFrame frame)
    {
    }
}
