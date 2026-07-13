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
import org.eclipse.jetty.quic.api.frames.ResetStreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.CongestionController;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.quic.common.QuicSession;
import org.eclipse.jetty.quic.common.QuicStream;
import org.eclipse.jetty.quic.common.frames.GeneratedFrame;
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

    @Override
    public void sendAcknowledgment(Packet.WithFrames packet, Callback callback)
    {
        // Differently from INITIAL and HANDSHAKE,
        // ONE_RTT acknowledgments may be delayed.
        acknowledger.sendAcknowledgment(packet, callback);
    }

    boolean processMaxData(QuicStream stream, long maxData)
    {
        try (var _ = lock())
        {
            MaxDataEntry entry = new MaxDataEntry(stream, maxData, Callback.NOOP);
            boolean result = maxDataEntries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("offered={} {} on {}", result, entry, this);
            return result;
        }
    }

    @Override
    boolean drain(List<FramesEntry> output, List<FramesEntry> ccOutput)
    {
        acknowledger.drain(output);
        return super.drain(output, ccOutput);
    }

    @Override
    boolean process(List<FramesEntry> processing, List<FramesEntry> ccProcessing, boolean probeMode) throws Exception
    {
        // Update the flow control windows.
        processMaxData();

        if (probeMode)
            return super.process(processing, ccProcessing, true);

        // Check pacing.
        CongestionController congestionController = getQuicSession().getCongestionController();
        long pacingDelay = congestionController.getPacingDelay();
        if (LOG.isDebugEnabled())
            LOG.debug("pacing delay {} ns on {}", pacingDelay, this);
        if (pacingDelay > getQuicFlusher().getPacingDelayThreshold())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("delayed by pacing, flush stalled on {}", this);
            Runnable task = () -> getQuicSession().getExecutor().execute(getQuicFlusher()::iterate);
            getQuicSession().getScheduler().schedule(task, pacingDelay, TimeUnit.NANOSECONDS);
            // Simulate a zero congestion window to stall data flush.
            return process(processing, ccProcessing, false, 0);
        }

        return super.process(processing, ccProcessing, false);
    }

    private void processMaxData()
    {
        try (var _ = lock())
        {
            maxDataProcessing.addAll(maxDataEntries);
            maxDataEntries.clear();
        }

        QuicSession session = getQuicSession();
        for (MaxDataEntry entry : maxDataProcessing)
        {
            QuicStream stream = entry.stream();
            if (stream == null)
                session.updateSendMaxOffset(entry.maxData());
            else
                stream.updateSendMaxOffset(entry.maxData());
        }
        maxDataProcessing.clear();
    }

    @Override
    GeneratedFrame generateFrame(RetainableByteBuffer.Mutable framesAccumulator, QuicStream stream, Frame frame, long maxBytes)
    {
        return switch (frame)
        {
            case StreamFrame streamFrame ->
            {
                if (streamFrame.offset() < 0)
                {
                    // Allow sending of zero-length StreamFrames even if the flow control window is zero.

                    long dataLength = streamFrame.remaining();

                    // Check whether we need to send data blocked frames.
                    if (!checkFlowControl(stream, dataLength))
                        yield null;

                    QuicSession session = stream.getSession();
                    long maxData = Math.min(session.getSendWindow(), stream.getSendWindow());

                    long offset = stream.getSentOffset();
                    if (LOG.isDebugEnabled())
                        LOG.debug("generating offset={} {} for stream {} on {}", offset, frame, stream, this);
                    GeneratedFrame generated = getFramesGenerator().generateStreamFrame(framesAccumulator, streamFrame, offset, maxData, maxBytes);
                    if (generated == null)
                        yield null;
                    long remaining = streamFrame.remaining();
                    dataLength = dataLength - remaining;

                    // Offsets are updated only on first transmission, not on
                    // retransmissions, because the flow control is limit-based.
                    session.updateSentOffset(session.getSentOffset() + dataLength);
                    stream.updateSentOffset(offset + dataLength);

                    // Check whether we need to send data blocked frames.
                    checkFlowControl(stream, remaining);

                    yield generated;
                }
                else
                {
                    // A retransmitted frame.
                    if (LOG.isDebugEnabled())
                        LOG.debug("re-generating {} for stream {} on {}", frame, stream, this);
                    long offset = streamFrame.offset() + (streamFrame.length() - streamFrame.remaining());
                    yield getFramesGenerator().generateStreamFrame(framesAccumulator, streamFrame, offset, Long.MAX_VALUE, maxBytes);
                }
            }
            case ResetStreamFrame resetStreamFrame ->
            {
                if (resetStreamFrame.finalSize() < 0)
                {
                    long finalSize = stream.getSentOffset();
                    if (LOG.isDebugEnabled())
                        LOG.debug("generating finalSize={} {} for stream {} on {}", finalSize, frame, stream, this);
                    yield getFramesGenerator().generateResetStreamFrame(framesAccumulator, resetStreamFrame, finalSize, maxBytes);
                }
                else
                {
                    // A retransmitted frame.
                    if (LOG.isDebugEnabled())
                        LOG.debug("re-generating {} for stream {} on {}", frame, stream, this);
                    yield getFramesGenerator().generateResetStreamFrame(framesAccumulator, resetStreamFrame, resetStreamFrame.finalSize(), maxBytes);
                }
            }
            default -> super.generateFrame(framesAccumulator, stream, frame, maxBytes);
        };
    }

    private boolean checkFlowControl(QuicStream stream, long dataLength)
    {
        QuicSession session = stream.getSession();
        long sessionWindow = session.getSendWindow();
        if (sessionWindow <= 0 && dataLength > 0)
        {
            if (session.stallFlowControl())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stalling session flow control for {} on {}", session, this);
                sendFrames(List.of(new DataBlockedFrame(session.getSentOffset())), NOOP);
            }
            return false;
        }
        long streamWindow = stream.getSendWindow();
        if (streamWindow <= 0 && dataLength > 0)
        {
            if (stream.stallFlowControl())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("stalling stream flow control for {} on {}", stream, this);
                sendFrames(List.of(new StreamDataBlockedFrame(stream.getId(), stream.getSentOffset())), NOOP);
            }
            return false;
        }
        return true;
    }

    record MaxDataEntry(QuicStream stream, long maxData, Callback callback) implements QuicFlusher.Entry
    {
    }

    /// Records acknowledgements for received packets and organizes them in an [AckFrame] to be sent.
    ///
    /// The [AckFrame] is sent immediately when these conditions apply:
    ///
    /// * RFC-9000 #13.2.2: there are at least two packets to acknowledge.
    /// * RFC-9000 #13.2.1: there is a gap in the packet numbering of received
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
                    // RFC-9000 #13.2.1: ack immediately to help loss detection at the sender.
                    drain = true;
                }
                else if (!packetNumbers.isEmpty())
                {
                    // RFC-9000 #13.2.2: ack immediately when two packets have been received.
                    drain = true;
                }
                else if (ackDelayTask == null)
                {
                    QuicSession session = getQuicSession();
                    Runnable task = () -> session.getExecutor().execute(this);
                    ackDelayTask = session.getScheduler().schedule(task, session.getQuicConfiguration().getAcknowledgmentMaxDelay(), TimeUnit.MILLISECONDS);
                }

                if (drain && ackDelayTask != null)
                    ackDelayTask.cancel();

                largestPacketNumber = Math.max(largestPacketNumber, packetNumber);
                packetNumbers.add(new Entry(NanoTime.now(), packetNumber, callback));
            }

            if (LOG.isDebugEnabled())
                LOG.debug("sending acks {} for {} on {}", drain ? "immediately" : "delayed", packet, this);

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

        void drain(List<FramesEntry> output)
        {
            List<Entry> numbers;
            try (var _ = lock())
            {
                numbers = new ArrayList<>(packetNumbers);
                packetNumbers.clear();
            }

            if (numbers.isEmpty())
                return;

            if (LOG.isDebugEnabled())
                LOG.debug("draining {} acks on {}", numbers.size(), this);

            long exponent = getQuicSession().getQuicConfiguration().getAcknowledgmentDelayExponent();

            if (numbers.size() == 1)
            {
                Entry entry = numbers.removeFirst();
                long ackDelayMicros = TimeUnit.NANOSECONDS.toMicros(NanoTime.since(entry.nanoTime));
                AckFrame frame = new AckFrame(entry.packetNumber(), AckFrame.encodeAckDelay(ackDelayMicros, exponent), 0, List.of());
                if (LOG.isDebugEnabled())
                    LOG.debug("draining {} on {}", frame, this);
                output.add(new FramesEntry(null, List.of(frame), entry.callback, false));
                return;
            }

            // RFC-9000[19.3, 19.3.1].
            // Packet numbers are in increasing order, but AckFrame needs a reverse order.
            // Example: [4, 5, 9, 10, 11, 13] should result in:
            // AckFrame[(13, 0), (0, 2), (2, 1)].

            // Make sure the list is sorted, in case some packet arrives out-of-order,
            // since the algorithm below assumes the packet numbers are sorted.
            Collections.sort(numbers);

            // Calculate the first range.
            int startIndex = numbers.size() - 1;
            Entry largest = numbers.get(startIndex);
            Callback combinedCallback = largest.callback();
            int index = startIndex - 1;
            while (index >= 0)
            {
                Entry previous = numbers.get(index + 1);
                Entry current = numbers.get(index);
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
                Entry end = numbers.get(endIndex);
                Entry begin = numbers.get(index);
                long rangeGap = end.packetNumber() - begin.packetNumber() - 2;

                startIndex = index;
                Entry start = numbers.get(startIndex);
                combinedCallback = Callback.combine(combinedCallback, start.callback());
                index = startIndex - 1;
                while (index >= 0)
                {
                    Entry previous = numbers.get(index + 1);
                    Entry current = numbers.get(index);
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

            long ackDelayMicros = TimeUnit.NANOSECONDS.toMicros(NanoTime.since(largest.nanoTime()));
            long encodedAckDelay = AckFrame.encodeAckDelay(ackDelayMicros, exponent);
            AckFrame frame = new AckFrame(largest.packetNumber(), encodedAckDelay, firstRangeLength, ackRanges != null ? ackRanges : List.of());
            if (LOG.isDebugEnabled())
                LOG.debug("draining {} on {}", frame, this);

            output.add(new FramesEntry(null, List.of(frame), combinedCallback, false));
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
