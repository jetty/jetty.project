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

import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuicFlusher extends IteratingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicFlusher.class);

    private final PacketFlusher packetFlusher = new PacketFlusher(this);
    private final CryptoFlusher initialFlusher = new CryptoFlusher(this, EncryptionLevel.INITIAL);
    private final CryptoFlusher handshakeFlusher = new CryptoFlusher(this, EncryptionLevel.HANDSHAKE);
    private final OneRTTFlusher oneRTTFlusher = new OneRTTFlusher(this);
    private final QuicSession session;
    private final FramesGenerator framesGenerator;
    private final PacketsGenerator packetsGenerator;
    private final RetainableByteBuffer.Mutable plaintextBuffer;
    private final RetainableByteBuffer.Mutable encryptedBuffer;
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

    /// Sends the given packet.
    ///
    /// This method should be called for [RetryPacket] and [VersionNegotiationPacket].
    ///
    /// @param packet the [Packet] to send
    /// @param callback the [Callback] to notify when the send is complete
    public boolean offer(Packet packet, Callback callback)
    {
        return packetFlusher.offer(packet, callback);
    }

    /// Offers the given list of session frames to send at the given [EncryptionLevel].
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

    /// Offers the [MaxDataFrame] to update the session send max data.
    ///
    /// @param frame the [MaxDataFrame] with the session send max data update
    public boolean offer(MaxDataFrame frame)
    {
        return oneRTTFlusher.offer(null, frame.maxData());
    }

    /// Offers the [StreamMaxDataFrame] for the given stream to update the stream send max data.
    ///
    /// @param stream the stream
    /// @param frame the [StreamMaxDataFrame] with the stream send max data update
    public boolean offer(QuicStream stream, StreamMaxDataFrame frame)
    {
        return oneRTTFlusher.offer(stream, frame.maxData());
    }

    /// Offers the given list of session frames to send at [EncryptionLevel#ONE_RTT].
    ///
    /// @param frames the list of frames to send
    /// @param callback the [Callback] to notify when the send is complete
    public boolean offer(List<Frame> frames, Callback callback)
    {
        return oneRTTFlusher.offer(null, frames, callback);
    }

    /// Offers the given list of stream frames to send at [EncryptionLevel#ONE_RTT].
    ///
    /// @param stream the stream
    /// @param frames the list of frames to send
    /// @param callback the [Callback] to notify when the send is complete
    public boolean offer(QuicStream stream, List<Frame> frames, Callback callback)
    {
        return oneRTTFlusher.offer(stream, frames, callback);
    }

    @Override
    protected Action process() throws Throwable
    {
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
        encryptedBuffer.clear();
        plaintextBuffer.clear();
        flusher.failed(cause);
        flusher = null;
    }

    public void resetCrypto()
    {
        initialFlusher.resetCrypto();
    }

    sealed interface Entry extends Callback permits PacketFlusher.PacketEntry, OneRTTFlusher.MaxDataEntry, FramesEntry
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

    record FramesEntry(QuicStream stream, List<Frame> frames, Callback callback) implements Entry
    {
    }
}
