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

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.frames.FramesGenerator;
import org.eclipse.jetty.quic.common.internal.packets.PacketsGenerator;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CryptoFlusher implements Callback
{
    private static final Logger LOG = LoggerFactory.getLogger(CryptoFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final Deque<QuicFlusher.FramesEntry> framesEntries = new ArrayDeque<>();
    private final List<QuicFlusher.FramesEntry> processingFramesEntries = new ArrayList<>();
    private final QuicFlusher flusher;
    private final EncryptionLevel encryptionLevel;
    private long cryptoOffset;

    CryptoFlusher(QuicFlusher flusher, EncryptionLevel encryptionLevel)
    {
        this.flusher = flusher;
        this.encryptionLevel = encryptionLevel;
    }

    boolean offer(List<Frame> frames, Callback callback)
    {
        try (var _ = lock.lock())
        {
            // TODO: check if closed/failed, etc.
            QuicFlusher.FramesEntry entry = new QuicFlusher.FramesEntry(null, frames, callback);
            boolean result = framesEntries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("offered={} {} on {}", result, entry, this);
            return result;
        }
    }

    boolean process() throws Exception
    {
        RetainableByteBuffer.Mutable framesAccumulator = flusher.getPlaintextBuffer();
        QuicSession session = flusher.getQuicSession();
        int packetHeaderLength = session.estimatePacketHeaderLength(encryptionLevel);
        long maxBytes = session.getUDPPayloadLength() - packetHeaderLength;
        try (var _ = lock.lock())
        {
            while (true)
            {
                QuicFlusher.FramesEntry framesEntry = framesEntries.poll();
                if (framesEntry == null)
                    break;

                boolean processed = true;
                boolean progress = false;
                FramesGenerator framesGenerator = flusher.getFramesGenerator();
                List<Frame> frames = framesEntry.frames();
                for (int i = 0; i < frames.size(); ++i)
                {
                    Frame frame = frames.get(i);
                    long generated = switch (frame)
                    {
                        case CryptoFrame cryptoFrame ->
                        {
                            long initialDataBytes = cryptoFrame.data().size();
                            long frameBytesGenerated = framesGenerator.generateCryptoFrame(framesAccumulator, cryptoFrame, cryptoOffset, maxBytes);
                            cryptoOffset += initialDataBytes - cryptoFrame.data().size();
                            yield frameBytesGenerated;
                        }
                        default -> framesGenerator.generateFrame(framesAccumulator, frame, maxBytes);
                    };
                    maxBytes -= generated;
                    progress |= generated > 0;

                    if (generated == 0 || maxBytes == 0)
                    {
                        if (!progress)
                            throw new QuicException(ErrorCode.INTERNAL_ERROR, "frame_generation_failure", frame.type());

                        // Only some frames of the entry could be generated, split the entry.
                        Callback callback = framesEntry.callback();
                        // The first half does not notify successful completion
                        // until all frames are processed but does notify failures.
                        framesEntry = new QuicFlusher.FramesEntry(framesEntry.stream(), frames.subList(0, i), Callback.from(callback.getInvocationType(), () ->
                        {}, callback::failed));
                        processingFramesEntries.add(framesEntry);

                        // Re-offer the second half.
                        QuicFlusher.FramesEntry remainingFramesEntry = new QuicFlusher.FramesEntry(framesEntry.stream(), frames.subList(i, frames.size()), callback);
                        framesEntries.offerFirst(remainingFramesEntry);

                        // Cannot generate more, so not fully processed.
                        processed = false;
                        break;
                    }
                }

                if (processed)
                    processingFramesEntries.add(framesEntry);
                else
                    break;
            }
        }

        if (processingFramesEntries.isEmpty())
            return false;

        RetainableByteBuffer.Mutable packetAccumulator = flusher.getEncryptedBuffer();
        PacketsGenerator packetGenerator = flusher.getPacketsGenerator();
        EndPoint endPoint = session.getEndPoint();

        List<Frame> frames = processingFramesEntries.size() == 1 ?
            processingFramesEntries.getFirst().frames() :
            processingFramesEntries.stream()
                .flatMap(entry -> entry.frames().stream())
                .toList();
        Packet packet = session.newPacket(encryptionLevel, frames);
        packetGenerator.generate(packetAccumulator, packet, framesAccumulator);
        session.notifyOutgoingPacket(packet);
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} {} to {} on {}", packet, packetAccumulator, endPoint, this);
        endPoint.write(flusher, session.getRemoteSocketAddress(), packetAccumulator.getByteBuffer());
        return true;
    }

    @Override
    public void succeeded()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("write succeeded to {} on {}", flusher.getQuicSession().getEndPoint(), this);
        processingFramesEntries.forEach(QuicFlusher.FramesEntry::succeeded);
        processingFramesEntries.clear();
    }

    @Override
    public void failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.atDebug().setCause(x).log("write failed to {} on {}", flusher.getQuicSession().getEndPoint(), this);
        processingFramesEntries.forEach(e -> e.failed(x));
        processingFramesEntries.clear();
        // TODO: fail the queued entries.
    }

    void resetCrypto()
    {
        cryptoOffset = 0;
    }
}
