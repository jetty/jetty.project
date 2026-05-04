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

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.PingFrame;
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
    private final List<FramesEntry> entries = new ArrayList<>();
    private final List<FramesEntry> ccEntries = new ArrayList<>();
    private final List<FramesEntry> processing = new ArrayList<>();
    private final List<FramesEntry> writing = new ArrayList<>();
    private final QuicFlusher flusher;
    private final EncryptionLevel encryptionLevel;
    private long cryptoOffset;
    private boolean sendProbe;

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
        boolean flush;
        try (var _ = lock())
        {
            // TODO: check if closed/failed, etc.
            AckFrame frame = new AckFrame(packet.packetNumber(), 0, 0, List.of());
            FramesEntry entry = new FramesEntry(null, List.of(frame), callback, false);
            flush = entries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("offered={} {} on {}", flush, entry, this);
        }
        if (flush)
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
            FramesEntry entry = new FramesEntry(stream, frames, callback, true);
            boolean result = ccEntries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("offered={} {} on {}", result, entry, this);
            return result;
        }
    }

    boolean sendProbe()
    {
        try (var _ = lock())
        {
            // TODO: check if closed/failed, etc.
            sendProbe = true;
            return true;
        }
    }

    boolean process() throws Exception
    {
        boolean probeMode = drain(processing);

        if (processing.isEmpty())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("no entries to flush on {}", this);
            return false;
        }

        boolean processed = process(processing, probeMode);

        try (var _ = lock())
        {
            sendProbe = false;
            for (int i = 0; i < processing.size(); ++i)
            {
                FramesEntry entry = processing.get(i);
                if (entry.congestionControlled())
                {
                    if (i > 0)
                        entries.addAll(0, processing.subList(0, i));
                    ccEntries.addAll(0, processing.subList(i, processing.size()));
                    processing.clear();
                    break;
                }
            }
        }

        return processed;
    }

    boolean drain(List<FramesEntry> output)
    {
        try (var _ = lock())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("draining {} non-cc entries on {}", entries.size(), this);
            output.addAll(entries);
            entries.clear();

            if (sendProbe)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("draining probe on {}", this);
                output.add(new FramesEntry(null, List.of(PingFrame.INSTANCE), Callback.NOOP, false));
            }

            if (LOG.isDebugEnabled())
                LOG.debug("draining {} cc entries on {}", ccEntries.size(), this);
            output.addAll(ccEntries);
            ccEntries.clear();

            return sendProbe;
        }
    }

    boolean process(List<FramesEntry> entries, boolean probeMode) throws Exception
    {
        long congestionWindow = getQuicSession().getCongestionController().getCongestionWindow();
        if (LOG.isDebugEnabled())
            LOG.debug("congestion window {} bytes on {}", congestionWindow, this);
        return process(entries, probeMode, congestionWindow);
    }

    boolean process(List<FramesEntry> processing, boolean probeMode, long congestionWindow) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} entries on {}", processing.size(), this);

        QuicSession session = getQuicSession();
        int packetHeaderLength = session.estimatePacketHeaderLength(encryptionLevel);
        long udpPayloadBytes = session.getUDPPayloadLength() - packetHeaderLength;
        // When sending a probe, leave at least 1 byte for the PingFrame.
        if (probeMode)
            udpPayloadBytes -= 1;

        List<Frame> framesLeft = new ArrayList<>();
        List<Frame> framesGenerated = new ArrayList<>();
        RetainableByteBuffer.Mutable framesAccumulator = flusher.getPlaintextBuffer();

        ListIterator<FramesEntry> iterator = processing.listIterator();

        // Loop over non-congestion-controlled entries.
        while (iterator.hasNext())
        {
            FramesEntry entry = iterator.next();
            if (entry.congestionControlled())
            {
                // Finished with the non-congestion-controlled entries.
                iterator.previous();
                break;
            }

            List<Frame> frames = entry.frames();
            for (int i = 0; i < frames.size(); ++i)
            {
                Frame frame = frames.get(i);
                if (LOG.isDebugEnabled())
                    LOG.debug("generating left={} {} on {}", udpPayloadBytes, frame, this);
                GeneratedFrame generated = generateFrame(framesAccumulator, entry.stream(), frame, udpPayloadBytes);
                if (LOG.isDebugEnabled())
                    LOG.debug("generated {} on {}", generated, this);

                if (generated == null)
                {
                    // Could not fit more in the datagram, bail out.
                    for (int j = i; j < frames.size(); ++j)
                    {
                        framesLeft.add(frames.get(j));
                    }
                    break;
                }
                else
                {
                    udpPayloadBytes -= generated.length();
                    assert udpPayloadBytes >= 0;
                    framesGenerated.add(generated.frame());
                }
            }

            assert !framesGenerated.isEmpty();

            if (framesLeft.isEmpty())
            {
                // The entry was fully generated, remove it from the processing list.
                writing.add(new FramesEntry(entry.stream(), List.copyOf(framesGenerated), entry.callback(), false));
                framesGenerated.clear();
                iterator.remove();
            }
            else
            {
                // The entry was partially generated, split it into two entries.
                Callback callback = entry.callback();
                // The first half does not notify successful completion
                // until all frames are processed but does notify failures.
                Callback firstHalfCallback = Callback.from(callback.getInvocationType(), () -> {}, callback::failed);
                FramesEntry firstHalfEntry = new FramesEntry(entry.stream(), List.copyOf(framesGenerated), firstHalfCallback, false);
                framesGenerated.clear();
                writing.add(firstHalfEntry);
                // Update the current entry with the second half.
                FramesEntry secondHalfEntry = new FramesEntry(entry.stream(), List.copyOf(framesLeft), callback, false);
                framesLeft.clear();
                iterator.set(secondHalfEntry);
                break;
            }
        }

        // When sending a probe, restore the space for the PingFrame
        // and don't limit with the congestion window.
        if (probeMode)
            udpPayloadBytes += 1;
        else
            udpPayloadBytes = Math.min(udpPayloadBytes, congestionWindow);

        // Loop over congestion-controlled entries.
        while (iterator.hasNext())
        {
            FramesEntry entry = iterator.next();

            List<Frame> frames = entry.frames();
            for (Frame frame : frames)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("generating left={} {} on {}", udpPayloadBytes, frame, this);
                GeneratedFrame generated = generateFrame(framesAccumulator, entry.stream(), frame, udpPayloadBytes);
                if (LOG.isDebugEnabled())
                    LOG.debug("generated {} on {}", generated, this);

                if (generated == null)
                {
                    framesLeft.add(frame);
                }
                else
                {
                    udpPayloadBytes -= generated.length();
                    assert udpPayloadBytes >= 0;

                    framesGenerated.add(generated.frame());

                    if (frame instanceof Frame.WithData dataFrame && dataFrame.remaining() > 0)
                        framesLeft.add(frame);
                }
            }

            if (framesGenerated.isEmpty())
            {
                // No frame was generated, keep the entry
                // as is and try to generate the next entry.
                framesLeft.clear();
            }
            else
            {
                if (framesLeft.isEmpty())
                {
                    // The entry was fully generated, remove it from the processing list.
                    writing.add(new FramesEntry(entry.stream(), List.copyOf(framesGenerated), entry.callback(), entry.congestionControlled()));
                    framesGenerated.clear();
                    iterator.remove();
                }
                else
                {
                    // The entry was partially generated, split it into two entries.
                    Callback callback = entry.callback();
                    // The first half does not notify successful completion
                    // until all frames are processed but does notify failures.
                    Callback firstHalfCallback = Callback.from(callback.getInvocationType(), () -> {}, callback::failed);
                    FramesEntry firstHalfEntry = new FramesEntry(entry.stream(), List.copyOf(framesGenerated), firstHalfCallback, entry.congestionControlled());
                    framesGenerated.clear();
                    writing.add(firstHalfEntry);
                    // Update the current entry with the second half.
                    FramesEntry secondHalfEntry = new FramesEntry(entry.stream(), List.copyOf(framesLeft), callback, entry.congestionControlled());
                    framesLeft.clear();
                    iterator.set(secondHalfEntry);
                }
            }
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
            : writing.stream().flatMap(entry -> entry.frames().stream()).toList();
        Packet packet = session.newPacket(encryptionLevel, frames);
        packetGenerator.generate(packetAccumulator, packet, framesAccumulator);
        session.notifyOutgoingPacket(packet);
        boolean dataStalled = processing.isEmpty() || session.getSendWindow(null) == 0;
        session.getPacketTracker().processPacketSent(session, packet, packetAccumulator.size(), dataStalled);
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

    record FramesEntry(QuicStream stream, List<Frame> frames, Callback callback, boolean congestionControlled) implements QuicFlusher.Entry
    {
    }
}
