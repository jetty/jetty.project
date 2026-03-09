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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class StreamFlusher extends CryptoFlusher
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamFlusher.class);

    private final Acknowledger acknowledger = new Acknowledger();
    private final List<MaxDataEntry> maxDataEntries = new ArrayList<>();
    private final List<MaxDataEntry> maxDataProcessing = new ArrayList<>();

    StreamFlusher(QuicFlusher flusher)
    {
        super(flusher, EncryptionLevel.ONE_RTT);
    }

    public void sendAcknowledgment(Packet.WithFrames packet, Callback callback)
    {
        acknowledger.sendAcknowledgment(packet, callback);
    }

    boolean processMaxData(QuicStream stream, long maxData)
    {
        try (var _ = lock())
        {
            // TODO: check if closed/failed, etc.
            MaxDataEntry entry = new MaxDataEntry(stream, maxData, Callback.NOOP);
            boolean result = maxDataEntries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("offered={} {} on {}", result, entry, this);
            return result;
        }
    }

    boolean process() throws Exception
    {
        try (var _ = lock())
        {
            maxDataProcessing.addAll(maxDataEntries);
            maxDataEntries.clear();
        }

        QuicSession session = getQuicSession();
        for (MaxDataEntry entry : maxDataProcessing)
        {
            session.updateSendMaxData(entry.stream(), entry.maxData());
            session.notifyMaxData(entry.stream(), entry.maxData());
        }
        maxDataProcessing.clear();

        return super.process();
    }

    @Override
    void lockedDrainTo(List<FramesEntry> output)
    {
        super.lockedDrainTo(output);
        acknowledger.lockedDrainTo(output);
    }

    @Override
    long generateFrame(RetainableByteBuffer.Mutable framesAccumulator, QuicStream stream, Frame frame, long maxBytes)
    {
        return switch (frame)
        {
            case StreamFrame streamFrame ->
            {
                QuicSession session = getQuicSession();
                long sessionWindow = session.getSendWindow(null);
                if (sessionWindow == 0)
                {
                    if (session.stall())
                    {
                        // TODO: optimize immediate generation if there is room.
                        sendFrames(null, List.of(new DataBlockedFrame(session.getSendData(null))), NOOP/*TODO: failures*/);
                    }
                    yield 0;
                }

                long streamWindow = session.getSendWindow(stream);
                if (streamWindow == 0)
                {
                    if (stream.stall())
                    {
                        // TODO: optimize immediate generation if there is room.
                        sendFrames(null, List.of(new StreamDataBlockedFrame(stream.getId(), session.getSendData(stream))), NOOP/*TODO: failures*/);
                    }
                    yield 0;
                }

                long sendWindow = Math.min(sessionWindow, streamWindow);
                maxBytes = Math.min(sendWindow, maxBytes);

                long offset = session.getSendData(stream);
                if (LOG.isDebugEnabled())
                    LOG.debug("generating offset={} {} for stream {} on {}", offset, frame, stream, this);
                long initial = streamFrame.data().size();
                long frameBytesGenerated = getFramesGenerator().generateStreamFrame(framesAccumulator, streamFrame, offset, maxBytes);
                long dataBytes = initial - streamFrame.data().size();
                session.updateSendData(stream, dataBytes);
                yield frameBytesGenerated;
            }
            default -> super.generateFrame(framesAccumulator, stream, frame, maxBytes);
        };
    }

    record MaxDataEntry(QuicStream stream, long maxData, Callback callback) implements QuicFlusher.Entry
    {
    }

    /// Records acknowledgements for received packets and organizes them in an [AckFrame] to be sent.
    ///
    /// The [AckFrame] is sent immediately when these conditions apply:
    ///
    /// * RFC-9000\[13.2.2]: there are at least two packets to acknowledge.
    /// * RFC-9000\[13.2.1]: there is a gap in the packet numbering of received
    /// packets (packet loss) or the received packet is out-of-order.
    ///
    /// Otherwise, the [AckFrame] send is delayed and scheduled to be sent either after
    /// the [QuicConfiguration#getAcknowledgmentMaxDelay()], or piggybacked when other
    /// frames are being sent.
    private class Acknowledger implements Runnable
    {
        private final List<Entry> packetNumbers = new ArrayList<>();
        private long largestPacketNumber;
        private Scheduler.Task ackDelayTask;

        public void sendAcknowledgment(Packet.WithFrames packet, Callback callback)
        {
            boolean drain = false;
            try (var _ = lock())
            {
                long packetNumber = packet.packetNumber();
                if (packetNumber - largestPacketNumber > 1 || packetNumber < largestPacketNumber)
                {
                    // RFC-9000[13.2.1]: ack immediately to help loss detection at the sender.
                    drain = true;
                }
                else if (!packetNumbers.isEmpty())
                {
                    // RFC-9000[13.2.2]: ack immediately when two packets have been received.
                    drain = true;
                }
                else if (ackDelayTask == null)
                {
                    QuicSession session = getQuicSession();
                    ackDelayTask = session.getScheduler().schedule(this, session.getQuicConfiguration().getAcknowledgmentMaxDelay(), TimeUnit.MILLISECONDS);
                }

                largestPacketNumber = Math.max(largestPacketNumber, packetNumber);
                packetNumbers.add(new Entry(NanoTime.now(), packetNumber, callback));
            }

            if (LOG.isDebugEnabled())
                LOG.debug("sending acks {} for {} on {}", drain ? "immediately" : "delayed" , packet, this);

            if (drain)
                getQuicFlusher().iterate();
        }

        @Override
        public void run()
        {
            boolean flush;
            try (var _ = lock())
            {
                ackDelayTask = null;
                flush = packetNumbers.size() == 1;
            }
            if (flush)
                getQuicFlusher().iterate();
        }

        void lockedDrainTo(List<FramesEntry> output)
        {
            if (packetNumbers.isEmpty())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("no acks to flush on {}", this);
                return;
            }

            long exponent = getQuicSession().getQuicConfiguration().getAcknowledgmentDelayExponent();

            if (packetNumbers.size() == 1)
            {
                Entry entry = packetNumbers.removeFirst();
                long ackDelayMicros = TimeUnit.NANOSECONDS.toMicros(NanoTime.since(entry.nanoTime));
                AckFrame frame = new AckFrame(entry.packetNumber(), AckFrame.encodeAckDelay(ackDelayMicros, exponent), 0, List.of());
                if (LOG.isDebugEnabled())
                    LOG.debug("flushing {} on {}", frame, this);
                output.add(new FramesEntry(null, List.of(frame), entry.callback));
                return;
            }

            // RFC-9000[19.3, 19.3.1].
            // Packet numbers are in increasing order, but AckFrame needs a reverse order.
            // Example: [4, 5, 9, 10, 11, 13] should result in:
            // AckFrame[(13, 0), (0, 2), (2, 1)].

            // Make sure the list is sorted, in case some packet arrives out-of-order,
            // since the algorithm below assumes the packet numbers are sorted.
            Collections.sort(packetNumbers);

            // Calculate the first range.
            int startIndex = packetNumbers.size() - 1;
            Entry largest = packetNumbers.get(startIndex);
            Callback combinedCallback = largest.callback();
            int index = startIndex - 1;
            while (index >= 0)
            {
                Entry previous = packetNumbers.get(index + 1);
                Entry current = packetNumbers.get(index);
                // If the packet numbers are not consecutive, the range is complete.
                if (current.packetNumber() != previous.packetNumber() - 1)
                    break;
                combinedCallback = Callback.combine(combinedCallback, current.callback());
                --index;
            }
            long firstRangeLength = startIndex - index - 1;

            // Calculate the other ranges.
            List<AckFrame.AckRange> ackRanges = null;
            while (index >= 0)
            {
                int endIndex = index + 1;
                Entry end = packetNumbers.get(endIndex);
                Entry begin = packetNumbers.get(index);
                long rangeGap = end.packetNumber() - begin.packetNumber() - 2;

                startIndex = index;
                Entry start = packetNumbers.get(startIndex);
                combinedCallback = Callback.combine(combinedCallback, start.callback());
                index = startIndex - 1;
                while (index >= 0)
                {
                    Entry previous = packetNumbers.get(index + 1);
                    Entry current = packetNumbers.get(index);
                    // If the packet numbers are not consecutive, the range is complete.
                    if (current.packetNumber() != previous.packetNumber() - 1)
                        break;
                    combinedCallback = Callback.combine(combinedCallback, current.callback());
                    --index;
                }
                int rangeLength = startIndex - index - 1;

                if (ackRanges == null)
                    ackRanges = new ArrayList<>();
                ackRanges.add(new AckFrame.AckRange(rangeGap, rangeLength));
            }

            packetNumbers.clear();

            long ackDelayMicros = TimeUnit.NANOSECONDS.toMicros(NanoTime.since(largest.nanoTime()));
            long encodedAckDelay = AckFrame.encodeAckDelay(ackDelayMicros, exponent);
            AckFrame frame = new AckFrame(largest.packetNumber(), encodedAckDelay, firstRangeLength, ackRanges != null ? ackRanges : List.of());
            if (LOG.isDebugEnabled())
                LOG.debug("flushing {} on {}", frame, this);

            output.add(new FramesEntry(null, List.of(frame), combinedCallback));
        }

        @Override
        public String toString()
        {
            int size;
            long largest;
            try (var _ = lock())
            {
                size = packetNumbers.size();
                largest = largestPacketNumber;
            }
            return "%s@%x[size=%d,largest=%d]".formatted(TypeUtil.toShortName(getClass()), hashCode(), size, largest);
        }

        private record Entry(long nanoTime, long packetNumber, Callback callback) implements Comparable<Entry>
        {
            @Override
            public int compareTo(Entry that)
            {
                return Long.compare(packetNumber(), that.packetNumber());
            }
        }
    }
}
