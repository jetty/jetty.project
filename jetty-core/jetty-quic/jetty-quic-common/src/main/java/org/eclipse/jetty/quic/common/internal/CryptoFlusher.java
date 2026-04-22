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
import java.util.ListIterator;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.quic.common.frames.FramesGenerator;
import org.eclipse.jetty.quic.common.frames.GeneratedFrame;
import org.eclipse.jetty.quic.common.internal.packets.PacketsGenerator;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class CryptoFlusher implements Callback
{
    private static final Logger LOG = LoggerFactory.getLogger(CryptoFlusher.class);

    private final AutoLock lock = new AutoLock();
    private final Deque<FramesEntry> entries = new ArrayDeque<>();
    private final List<FramesEntry> processing = new ArrayList<>();
    private final List<FramesEntry> writing = new ArrayList<>();
    private final QuicFlusher flusher;
    private final EncryptionLevel encryptionLevel;
    private long cryptoOffset;

    CryptoFlusher(QuicFlusher flusher, EncryptionLevel encryptionLevel)
    {
        this.flusher = flusher;
        this.encryptionLevel = encryptionLevel;
    }

    QuicFlusher getQuicFlusher()
    {
        return flusher;
    }

    EncryptionLevel getEncryptionLevel()
    {
        return encryptionLevel;
    }

    QuicSession getQuicSession()
    {
        return flusher.getQuicSession();
    }

    FramesGenerator getFramesGenerator()
    {
        return flusher.getFramesGenerator();
    }

    AutoLock lock()
    {
        return lock.lock();
    }

    void sendAcknowledgment(Packet.WithFrames packet, Callback callback)
    {
        // RFC-9000[13.2.1]: initial and handshake packets must be acknowledged immediately.
        AckFrame frame = new AckFrame(packet.packetNumber(), 0, 0, List.of());
        if (sendFrames(List.of(frame), callback))
            getQuicFlusher().iterate();
    }

    boolean sendFrames(List<Frame> frames, Callback callback)
    {
        return sendFrames(null, frames, callback);
    }

    boolean sendFrames(QuicStream stream, List<Frame> frames, Callback callback)
    {
        try (var _ = lock())
        {
            // TODO: check if closed/failed, etc.
            FramesEntry entry = new FramesEntry(stream, frames, callback);
            boolean result = entries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("offered={} {} on {}", result, entry, this);
            return result;
        }
    }

    boolean process() throws Exception
    {
        // This class performs immediate processing of frame entries,
        // without taking into account pacing or congestion window,
        // which are taken into account by subclass StreamFlusher.
        return process(Long.MAX_VALUE);
    }

    boolean process(long congestionWindow) throws Exception
    {
        try (var _ = lock())
        {
            processing.addAll(entries);
            entries.clear();
        }

        if (processing.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("no entries to flush on {}", this);
            return false;
        }

        return process(processing, congestionWindow);
    }

    boolean process(List<FramesEntry> processing, long congestionWindow) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} entries on {}", processing.size(), this);

        QuicSession session = getQuicSession();
        int packetHeaderLength = session.estimatePacketHeaderLength(encryptionLevel);
        long packetPayloadMaxBytes = session.getUDPPayloadLength() - packetHeaderLength;
        long maxBytes = Math.min(packetPayloadMaxBytes, congestionWindow);

        RetainableByteBuffer.Mutable framesAccumulator = flusher.getPlaintextBuffer();
        ListIterator<FramesEntry> iterator = processing.listIterator();
        while (iterator.hasNext())
        {
            FramesEntry entry = iterator.next();
            boolean allFramesProcessed = true;
            List<Frame> framesGenerated = new ArrayList<>();
            List<Frame> frames = entry.frames();
            for (int i = 0; i < frames.size(); ++i)
            {
                Frame frame = frames.get(i);
                if (LOG.isDebugEnabled())
                    LOG.debug("generating {} udp/cwnd={}/{} on {}", frame, packetPayloadMaxBytes, maxBytes, this);
                GeneratedFrame generated = generateFrame(framesAccumulator, entry.stream(), frame, maxBytes);
                if (LOG.isDebugEnabled())
                    LOG.debug("generated {} on {}", generated, this);

                if (generated == null && i == 0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("could not generate frame {}, flush stalled on {}", frame, this);
                    allFramesProcessed = false;
                    break;
                }

                if (generated != null)
                {
                    maxBytes -= generated.length();
                    assert maxBytes >= 0;

                    framesGenerated.add(generated.frame());

                    if (!(frame instanceof Frame.WithData dataFrame) || dataFrame.remaining() == 0)
                        continue;
                }

                // Only some of, or part of, the frames of the entry could be generated, split the entry.
                // Non-data frames are either fully generated or not generated at all, while data frames
                // may be partially generated, so the entry must be split accordingly.

                Callback callback = entry.callback();
                // The first half does not notify successful completion
                // until all frames are processed but does notify failures.
                Callback firstHalfCallback = Callback.from(callback.getInvocationType(), () -> {}, callback::failed);

                FramesEntry firstHalfEntry = new FramesEntry(entry.stream(), framesGenerated, firstHalfCallback);
                writing.add(firstHalfEntry);

                // Update the current entry with the second half.
                FramesEntry secondHalfEntry = new FramesEntry(entry.stream(), frames.subList(i, frames.size()), callback);
                iterator.set(secondHalfEntry);

                allFramesProcessed = false;
                break;
            }

            if (!allFramesProcessed)
                break;

            writing.add(new FramesEntry(entry.stream(), framesGenerated, entry.callback()));
            iterator.remove();
        }

        if (writing.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("no entries to write on {}", this);
            return false;
        }

        // RFC-9001[5.4.2]: minimally pad the payload.
        // Packet protection requires 16 bytes of sample,
        // offset by 4 bytes from the packet number,
        // so there must be at least 4 bytes of payload.
        if (framesAccumulator.size() < 4)
            framesAccumulator.putInt(0);

        RetainableByteBuffer.Mutable packetAccumulator = flusher.getEncryptedBuffer();
        PacketsGenerator packetGenerator = flusher.getPacketsGenerator();
        EndPoint endPoint = session.getEndPoint();

        List<Frame> frames = writing.size() == 1
            ? writing.getFirst().frames()
            : writing.stream()
                .flatMap(entry -> entry.frames().stream())
                .toList();
        Packet packet = session.newPacket(encryptionLevel, frames);
        packetGenerator.generate(packetAccumulator, packet, framesAccumulator);
        session.notifyOutgoingPacket(packet, packetAccumulator.remaining());
        if (LOG.isDebugEnabled())
            LOG.debug("writing {} {} to {} on {}", packet, packetAccumulator, endPoint, this);
        endPoint.write(flusher, session.getRemoteSocketAddress(), packetAccumulator.getByteBuffer());
        return true;
    }

    GeneratedFrame generateFrame(RetainableByteBuffer.Mutable framesAccumulator, QuicStream stream, Frame frame, long maxBytes)
    {
        FramesGenerator framesGenerator = getFramesGenerator();
        return switch (frame)
        {
            case CryptoFrame cryptoFrame ->
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("generating offset={} {} for stream {} on {}", cryptoOffset, frame, stream, this);
                if (cryptoFrame.offset() < 0)
                {
                    GeneratedFrame generated = framesGenerator.generateCryptoFrame(framesAccumulator, cryptoFrame, cryptoOffset, maxBytes);
                    if (generated != null)
                        cryptoOffset += ((CryptoFrame)generated.frame()).remaining();
                    yield generated;
                }
                else
                {
                    // A retransmitted frame.
                    long offset = cryptoFrame.offset() + (cryptoFrame.length() - cryptoFrame.remaining());
                    yield framesGenerator.generateCryptoFrame(framesAccumulator, cryptoFrame, offset, maxBytes);
                }
            }
            default ->
            {
                long length = framesGenerator.generateFrame(framesAccumulator, frame, maxBytes);
                yield length == 0 ? null : new GeneratedFrame(frame, length);
            }
        };
    }

    @Override
    public void succeeded()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("write succeeded to {} on {}", getQuicSession().getEndPoint(), this);
        writing.forEach(FramesEntry::succeeded);
        writing.clear();
    }

    @Override
    public void failed(Throwable x)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("write failed to {} on {}", getQuicSession().getEndPoint(), this, x);
        writing.forEach(e -> e.failed(x));
        writing.clear();
        // TODO: fail the queued entries.
    }

    void resetCrypto()
    {
        cryptoOffset = 0;
    }

    @Override
    public String toString()
    {
        return "%s@%x[%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), getEncryptionLevel());
    }

    record FramesEntry(QuicStream stream, List<Frame> frames, Callback callback) implements QuicFlusher.Entry
    {
    }
}
