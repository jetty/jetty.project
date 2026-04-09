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

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.PingFrame;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Tracks packets sent and acknowledgments received to support reliability and
/// congestion control, as per [RFC-9002](https://datatracker.ietf.org/doc/html/rfc9002).
///
/// The basic design of QUIC loss detection is as follows:
///
/// * Packets sent schedule a probe timeout (PTO). The PTO is canceled when an
/// ack is received. The PTO mechanism covers the case where no acks are received.
/// * Acks received may schedule a loss timeout (LTO). The LTO mechanism covers the
/// case where acks are received, but packets might be lost.
/// A new LTO may be scheduled from a previous LTO.
/// * There is only one timeout mechanism active at any given time. The PTO is
/// scheduled for the latest packet sent, while the LTO is scheduled for the earliest
/// non-lost packet for which an ack is expected but has not been received yet.
/// If the system is idle, neither the PTO nor the LTO are active.
///
/// The corresponding events are:
///
/// * Packet sent
/// * Ack received
/// * PTO expires
/// * LTO expires
///
/// These events are serialized with respect to each other so that the flusher
/// can always see stable values from the [CongestionController].
///
/// The serialization of the processing of the events simplifies the implementation,
/// both of this class and of the [CongestionController] implementation.
///
/// This class maintains the round trip time (RTT), calculated as specified in
/// RFC-9002\[5].
///
/// Packet loss is detected by:
/// * Packet number: an acknowledgment for packet `N` is received, but not
/// for packet `N-r` where `r` is the [#getPacketReorderingThreshold()].
/// * Time: a packet is sent at time `T`, but no ack has been received
/// within time `T+u` where `u` is a time calculated from the RTT and
/// [#getTimeReorderingFactor()].
public class PacketTracker
{
    private static final Logger LOG = LoggerFactory.getLogger(PacketTracker.class);

    private final EnumMap<PacketNumberSpace, Tracker> trackers = new EnumMap<>(PacketNumberSpace.class);
    private final Scheduler scheduler;
    private final CongestionController congestionController;
    private long ackMaxDelay;
    private long ackDelayExponent;
    private int packetReorderingThreshold;
    private float timeReorderingFactor;
    private long probeTimeoutBackoff;
    private RTTData rttData;

    public PacketTracker(Scheduler scheduler, CongestionController congestionController)
    {
        this.scheduler = scheduler;
        this.congestionController = congestionController;
        trackers.put(PacketNumberSpace.INITIAL, new Tracker(PacketNumberSpace.INITIAL));
        trackers.put(PacketNumberSpace.HANDSHAKE, new Tracker(PacketNumberSpace.HANDSHAKE));
        trackers.put(PacketNumberSpace.APPLICATION, new Tracker(PacketNumberSpace.APPLICATION));
        // RFC-9002[6.1.1]: recommended initial values.
        packetReorderingThreshold = 3;
        timeReorderingFactor = 9F / 8;
        // RFC-9002[A.4]: initialization of RTT data.
        long smoothedRTT = TimeUnit.MILLISECONDS.toNanos(333);
        rttData = new RTTData(0, 0, smoothedRTT, smoothedRTT / 2);
    }

    public CongestionController getCongestionController()
    {
        return congestionController;
    }

    /// @return the acknowledgment max delay in milliseconds
    public long getAcknowledgmentMaxDelay()
    {
        return ackMaxDelay;
    }

    /// @param ackMaxDelay the acknowledgment max delay in milliseconds
    public void setAcknowledgmentMaxDelay(long ackMaxDelay)
    {
        this.ackMaxDelay = ackMaxDelay;
    }

    public long getAcknowledgmentDelayExponent()
    {
        return ackDelayExponent;
    }

    public void setAcknowledgmentDelayExponent(long ackDelayExponent)
    {
        this.ackDelayExponent = ackDelayExponent;
    }

    public int getPacketReorderingThreshold()
    {
        return packetReorderingThreshold;
    }

    public void setPacketReorderingThreshold(int packetReorderingThreshold)
    {
        this.packetReorderingThreshold = packetReorderingThreshold;
    }

    public float getTimeReorderingFactor()
    {
        return timeReorderingFactor;
    }

    public void setTimeReorderingFactor(float timeReorderingFactor)
    {
        this.timeReorderingFactor = timeReorderingFactor;
    }

    public long getProbeTimeoutBackoff()
    {
        return probeTimeoutBackoff;
    }

    /// First entry point: when a packet is sent.
    void processPacketSent(QuicSession session, Packet packet, long length)
    {
        // RFC-9002[A.5].

        // Packets without frames are not acknowledged, don't track them.
        if (!(packet instanceof Packet.WithFrames withFrames))
            return;

        if (!withFrames.requiresAcknowledgement())
            return;

        EncryptionLevel encryptionLevel = EncryptionLevel.from(withFrames);
        PacketNumberSpace space = PacketNumberSpace.from(encryptionLevel);
        PacketEntry packetEntry = new PacketEntry(space, withFrames, length, NanoTime.now());

        Tracker tracker = trackers.get(packetEntry.space());
        tracker.put(packetEntry.packet().packetNumber(), packetEntry);

        if (LOG.isDebugEnabled())
            LOG.debug("tracked {} on {}", packetEntry, this);

        congestionController.onPacketSent(packetEntry.packet(), packetEntry.length(), rttData);

        tracker.tryScheduleProbeTimeout(session, packetEntry, calculateProbeTimeout(packetEntry, rttData));
    }

    /// Second entry point: when an ack is received.
    public void processAckFrameReceived(QuicSession session, EncryptionLevel encryptionLevel, AckFrame frame)
    {
        AckEntry entry = new AckEntry(encryptionLevel, frame);

        if (LOG.isDebugEnabled())
            LOG.debug("acking {} on {}", entry, this);

        PacketNumberSpace space = PacketNumberSpace.from(entry.encryptionLevel());
        Tracker tracker = trackers.get(space);

        List<Packet.WithFrames> ackedPackets = new ArrayList<>();
        long ackedLength = tracker.acknowledgePackets(entry.frame(), ackedPackets);

        if (ackedPackets.isEmpty())
            return;

        // RFC-9002[5.1]: calculate RTT only if the largestAckedEntry is newly acknowledged.
        if (tracker.newlyAcked)
            rttData = estimateRTTData(entry.encryptionLevel(), entry.frame(), tracker.largestAckedEntry);
        tracker.newlyAcked = false;

        // RFC-9002[6.2.1]: the PTO backoff is not reset for InitialPackets.
        if (ackedPackets.stream().anyMatch(p -> EncryptionLevel.from(p) != EncryptionLevel.INITIAL))
            probeTimeoutBackoff = 0;
        tracker.cancelProbeTimeout();

        List<Packet.WithFrames> lostPackets = new ArrayList<>();
        long lostLength = tracker.detectLostPackets(session, rttData, lostPackets);

        congestionController.onPacketsAcknowledged(ackedPackets, ackedLength, rttData);
        if (!lostPackets.isEmpty())
        {
            congestionController.onPacketsLost(lostPackets, lostLength, rttData);
            retransmit(session, lostPackets);
        }

        tracker.tryScheduleProbeTimeout(session);
    }

    /// Third entry point: when the loss timeout fires.
    private void onLossTimeout(QuicSession session, Tracker tracker)
    {
        onTaskTimeout(session, () -> processLossTimeout(session, tracker));
    }

    /// Fourth entry point: when the probe timeout fires.
    private void onProbeTimeout(QuicSession session, PacketEntry packetEntry, Tracker tracker)
    {
        onTaskTimeout(session, () -> processProbeTimeout(session, packetEntry, tracker));
    }

    void onTaskTimeout(QuicSession session, Runnable task)
    {
        session.onScheduledTask(task);
    }

    private void processLossTimeout(QuicSession session, Tracker tracker)
    {
        tracker.processLossTimeout(session);
    }

    private void processProbeTimeout(QuicSession session, PacketEntry packetEntry, Tracker tracker)
    {
        tracker.probeTimeoutTask = null;
        ++probeTimeoutBackoff;
        sendProbe(session, EncryptionLevel.from(packetEntry.packet()));
    }

    void sendProbe(QuicSession session, EncryptionLevel encryptionLevel)
    {
        session.sendFrames(encryptionLevel, List.of(new PingFrame()), Callback.NOOP);
    }

    void retransmit(QuicSession session, List<Packet.WithFrames> lostPackets)
    {
        session.retransmit(lostPackets);
    }

    private RTTData estimateRTTData(EncryptionLevel encryptionLevel, AckFrame frame, PacketEntry largestEntry)
    {
        boolean firstSample = rttData.latestRTT() == 0;
        long latestRTT = NanoTime.since(largestEntry.sendNanoTime());

        RTTData newRTTData;
        if (firstSample)
        {
            newRTTData = new RTTData(latestRTT, latestRTT, latestRTT, latestRTT / 2);
        }
        else
        {
            // RFC-9002[5.2]: minRTT must be initially set to the initial RTT,
            // and then updated with the lesser of minRTT and latestRTT.
            long minimumRTT = Math.min(rttData.minimumRTT(), latestRTT);

            // RFC-9002[5.3]: rules for ackDelay.
            long ackDelayNanos = 0;
            if (encryptionLevel == EncryptionLevel.ONE_RTT)
            {
                long ackDelayMicros = AckFrame.decodeAckDelay(frame.encodedAckDelay(), getAcknowledgmentDelayExponent());
                ackDelayNanos = Math.min(TimeUnit.MICROSECONDS.toNanos(ackDelayMicros), TimeUnit.MILLISECONDS.toNanos(getAcknowledgmentMaxDelay()));
            }

            // RFC-9002[5.3]: calculate smoothedRTT and variationRTT.
            long adjustedRTT = latestRTT;
            if (latestRTT >= minimumRTT + ackDelayNanos)
                adjustedRTT = latestRTT - ackDelayNanos;
            // RFC-6298[2.3]: variation is calculated with the old smoothedRTT.
            // Note that RFC-9002[5.3] pseudocode calculates the variation
            // with the new smoothedRTT, but it is likely to be a mistake.
            long variation = Math.abs(rttData.smoothedRTT() - adjustedRTT);
            long smoothedRTT = (7 * rttData.smoothedRTT() + adjustedRTT) / 8;
            long variationRTT = (3 * rttData.variationRTT() + variation) / 4;
            newRTTData = new RTTData(latestRTT, minimumRTT, smoothedRTT, variationRTT);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("estimated {}", newRTTData);

        return newRTTData;
    }

    private long calculatePacketLossDelay(RTTData rttData)
    {
        return (long)(getTimeReorderingFactor() * Math.max(rttData.latestRTT(), rttData.smoothedRTT()));
    }

    private long calculateProbeTimeout(PacketEntry packetEntry, RTTData rttData)
    {
        // RFC-9002[6.2.1].
        long pto = rttData.smoothedRTT() + 4 * rttData.variationRTT();
        if (PacketNumberSpace.from(EncryptionLevel.from(packetEntry.packet())) == PacketNumberSpace.APPLICATION)
            pto += TimeUnit.MILLISECONDS.toNanos(getAcknowledgmentMaxDelay());

        // RFC-9002[A.8].
        return pto * (1L << getProbeTimeoutBackoff());
    }

    // Used only in tests.
    boolean hasLossTimeoutTask(EncryptionLevel encryptionLevel)
    {
        return trackers.get(PacketNumberSpace.from(encryptionLevel)).lossTimeoutTask != null;
    }

    // Used only in tests.
    boolean hasProbeTimeoutTask(EncryptionLevel encryptionLevel)
    {
        return trackers.get(PacketNumberSpace.from(encryptionLevel)).probeTimeoutTask != null;
    }

    @Override
    public String toString()
    {
        return "%s@%x%s".formatted(TypeUtil.toShortName(getClass()), hashCode(), trackers.values());
    }

    private class Tracker
    {
        private final LinkedHashMap<Long, PacketEntry> entries = new LinkedHashMap<>();
        private final PacketNumberSpace packetNumberSpace;
        private Scheduler.Task lossTimeoutTask;
        private Scheduler.Task probeTimeoutTask;
        private PacketEntry largestAckedEntry;
        private boolean newlyAcked;

        private Tracker(PacketNumberSpace packetNumberSpace)
        {
            this.packetNumberSpace = packetNumberSpace;
        }

        private void put(long packetNumber, PacketEntry entry)
        {
            PacketEntry existing = entries.put(packetNumber, entry);
            assert existing == null;
        }

        private PacketEntry remove(long packetNumber)
        {
            return entries.remove(packetNumber);
        }

        private long acknowledgePackets(AckFrame ackFrame, List<Packet.WithFrames> output)
        {
            long[] acknowledged = ackFrame.allAcknowledged();
            long ackedLength = 0;
            for (int i = 0; i < acknowledged.length; i++)
            {
                long packetNumber = acknowledged[i];
                PacketEntry entry = remove(packetNumber);
                if (i == 0 && entry != null && entry.largerThan(largestAckedEntry))
                {
                    largestAckedEntry = entry;
                    newlyAcked = true;
                }
                if (entry != null)
                {
                    ackedLength += entry.length();
                    Packet.WithFrames packet = entry.packet();
                    output.add(packet);
                    for (Frame frame : packet.frames())
                    {
                        if (frame instanceof Frame.WithData withData)
                            withData.release();
                    }
                }
            }
            if (LOG.isDebugEnabled())
                LOG.debug("acked {} packet(s) length={} {}{} on {}", output.size(), ackedLength, packetNumberSpace, output.stream().mapToLong(Packet.WithFrames::packetNumber).toArray(), this);
            return ackedLength;
        }

        private long detectLostPackets(QuicSession session, RTTData rttData, List<Packet.WithFrames> output)
        {
            // RFC-9002[A.10].

            // This method is called after receiving an ack,
            // or from the task scheduled in this method, so
            // there always is at least one acked entry.
            assert largestAckedEntry != null;

            long lostLength = 0;
            long lossDelay = calculatePacketLossDelay(rttData);
            long largestAckedPacketNumber = largestAckedEntry.packet().packetNumber();
            Iterator<PacketEntry> iterator = entries.values().iterator();
            while (iterator.hasNext())
            {
                PacketEntry entry = iterator.next();
                long packetNumber = entry.packet().packetNumber();

                // If the packet is in-flight, it is not lost.
                if (packetNumber > largestAckedPacketNumber)
                    break;

                // Detect loss by packet number.
                // Assumes the sender does not introduce packet number gaps.
                if (largestAckedPacketNumber >= packetNumber + getPacketReorderingThreshold())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("packet lost by number {}>{}+{} {} on {}", largestAckedPacketNumber, packetNumber, getPacketReorderingThreshold(), entry.packet(), this);
                    lostLength += entry.length();
                    output.add(entry.packet());
                    iterator.remove();
                    continue;
                }

                // Detect loss by send time.
                long sentDelay = NanoTime.since(entry.sendNanoTime());
                if (sentDelay >= lossDelay)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("packet lost by time sentDelay={} lossDelay={} {} on {}", sentDelay, lossDelay, entry.packet(), this);
                    lostLength += entry.length();
                    output.add(entry.packet());
                    iterator.remove();
                    continue;
                }

                // The packet is in-flight and not yet lost,
                // despite a packet with a larger number was
                // acked (reordering), schedule the loss timeout.
                long lossTimeout = lossDelay - sentDelay;
                tryScheduleLossTimeout(session, lossTimeout);
                break;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("lost {} packet(s) length={} {}{} on {}", output.size(), lostLength, packetNumberSpace, output.stream().mapToLong(Packet.WithFrames::packetNumber).toArray(), this);

            return lostLength;
        }

        private void tryScheduleLossTimeout(QuicSession session, long timeoutNanos)
        {
            if (lossTimeoutTask != null)
                lossTimeoutTask.cancel();

            if (LOG.isDebugEnabled())
                LOG.debug("scheduling loss timeout in {} ns on {}", timeoutNanos, this);

            Runnable task = () -> onLossTimeout(session, this);
            lossTimeoutTask = scheduler.schedule(task, timeoutNanos, TimeUnit.NANOSECONDS);
        }

        private void processLossTimeout(QuicSession session)
        {
            lossTimeoutTask = null;
            List<Packet.WithFrames> lostPackets = new ArrayList<>();
            long lostLength = detectLostPackets(session, rttData, lostPackets);
            if (!lostPackets.isEmpty())
            {
                congestionController.onPacketsLost(lostPackets, lostLength, rttData);
                retransmit(session, lostPackets);
            }
            tryScheduleProbeTimeout(session);
        }

        private void tryScheduleProbeTimeout(QuicSession session)
        {
            if (entries.isEmpty())
                return;
            PacketEntry packetEntry = entries.sequencedValues().getLast();
            tryScheduleProbeTimeout(session, packetEntry, calculateProbeTimeout(packetEntry, rttData));
        }

        private void tryScheduleProbeTimeout(QuicSession session, PacketEntry packetEntry, long timeoutNanos)
        {
            // RFC-9002[6.2.1]: do not set the PTO if LTO is present.
            if (lossTimeoutTask != null)
                return;

            cancelProbeTimeout();

            long delayNanos = timeoutNanos - NanoTime.since(packetEntry.sendNanoTime());

            if (LOG.isDebugEnabled())
                LOG.debug("scheduling probe timeout in {} ns on {}", delayNanos, this);

            Runnable task = () -> onProbeTimeout(session, packetEntry, this);
            probeTimeoutTask = scheduler.schedule(task, delayNanos, TimeUnit.NANOSECONDS);
        }

        private void cancelProbeTimeout()
        {
            if (probeTimeoutTask == null)
                return;

            probeTimeoutTask.cancel();
            probeTimeoutTask = null;
            if (LOG.isDebugEnabled())
                LOG.debug("cancelled probe timeout on {}", this);
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), packetNumberSpace, entries.keySet());
        }
    }

    private record PacketEntry(PacketNumberSpace space, Packet.WithFrames packet, long length, long sendNanoTime)
    {
        public boolean largerThan(PacketEntry other)
        {
            if (other == null)
                return true;
            return packet().packetNumber() > other.packet().packetNumber();
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s,#%d,length=%d]".formatted(TypeUtil.toShortName(getClass()), hashCode(), space(), packet().packetNumber(), length());
        }
    }

    private record AckEntry(EncryptionLevel encryptionLevel, AckFrame frame)
    {
        @Override
        public String toString()
        {
            return "%s@%x[%s,%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), PacketNumberSpace.from(encryptionLevel()), frame());
        }
    }
}
