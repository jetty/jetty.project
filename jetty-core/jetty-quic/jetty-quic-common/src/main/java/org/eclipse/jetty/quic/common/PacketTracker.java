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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketTracker extends IteratingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(PacketTracker.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<PacketEntry> packetEntries = new ArrayDeque<>();
    private final Queue<AckEntry> ackEntries = new ArrayDeque<>();
    private final Queue<Tracker> lossTrackers = new ArrayDeque<>();
    private final EnumMap<PacketNumberSpace, Tracker> trackers = new EnumMap<>(PacketNumberSpace.class);
    private final Scheduler scheduler;
    private final CongestionController congestionController;
    private long ackMaxDelay;
    private long ackDelayExponent;
    private int packetReorderingThreshold;
    private float timeReorderingFactor;
    private long schedulerResolution;
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
        schedulerResolution = TimeUnit.MILLISECONDS.toNanos(1);
        // RFC-9002[A.4]: initialization of RTT data.
        long smoothedRTT = TimeUnit.MILLISECONDS.toNanos(333);
        rttData = new RTTData(0, 0, smoothedRTT, smoothedRTT / 2);
    }

    public CongestionController getCongestionController()
    {
        return congestionController;
    }

    public long getAcknowledgmentMaxDelay()
    {
        return ackMaxDelay;
    }

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

    /// @return the scheduler resolution in nanoseconds
    public long getSchedulerResolution()
    {
        return schedulerResolution;
    }

    /// @param schedulerResolution the scheduler resolution in nanoseconds
    public void setSchedulerResolution(long schedulerResolution)
    {
        this.schedulerResolution = schedulerResolution;
    }

    public void onPacketSent(Packet packet, long length)
    {
        // Packets without frames are not acknowledged, don't track them.
        if (!(packet instanceof Packet.WithFrames withFrames))
            return;

        if (!withFrames.requiresAcknowledgement())
            return;

        PacketNumberSpace space = PacketNumberSpace.from(EncryptionLevel.from(withFrames));
        PacketEntry entry = new PacketEntry(space, withFrames, length, NanoTime.now());
        try (var _ = lock.lock())
        {
            packetEntries.offer(entry);
        }
        iterate();
    }

    private void processPacketsSent(List<PacketEntry> packets)
    {
        // RFC-9002[A.5].
        for (PacketEntry entry : packets)
        {
            Tracker tracker = trackers.get(entry.space());
            tracker.put(entry.packet().packetNumber(), entry);

            if (LOG.isDebugEnabled())
                LOG.debug("tracked {} on {}", entry, this);

            congestionController.onPacketSent(entry.packet(), entry.length(), rttData);

            tracker.schedulePacketLossDetection(rttData);
        }
    }

    public void onAckFrameReceived(EncryptionLevel encryptionLevel, AckFrame frame)
    {
        AckEntry ackEntry = new AckEntry(encryptionLevel, frame);
        try (var _ = lock.lock())
        {
            ackEntries.offer(ackEntry);
        }
        iterate();
    }

    private void processAckFramesReceived(List<AckEntry> acks)
    {
        // RFC-9002[A.7].
        for (AckEntry entry : acks)
        {
            PacketNumberSpace space = PacketNumberSpace.from(entry.encryptionLevel());
            Tracker tracker = trackers.get(space);

            List<Packet.WithFrames> ackedPackets = new ArrayList<>();
            long ackedLength = tracker.acknowledgePackets(entry.frame(), ackedPackets);

            if (LOG.isDebugEnabled())
                LOG.debug("acked {} packet(s) length={} {}{} on {}", ackedPackets.size(), ackedLength, space, ackedPackets.stream().mapToLong(Packet.WithFrames::packetNumber).toArray(), this);

            if (ackedPackets.isEmpty())
                continue;

            if (tracker.largestAckedEntry != null)
                rttData = estimateRTTData(entry.encryptionLevel(), entry.frame(), tracker.largestAckedEntry);

            List<Packet.WithFrames> lostPackets = new ArrayList<>();
            long lostLength = tracker.detectLostPackets(rttData, lostPackets);

            if (LOG.isDebugEnabled())
                LOG.debug("lost {} packet(s) length={} {}{} on {}", lostPackets.size(), lostLength, space, lostPackets.stream().mapToLong(Packet.WithFrames::packetNumber).toArray(), this);

            if (!ackedPackets.isEmpty())
                congestionController.onPacketsAcknowledged(ackedPackets, ackedLength, rttData);
            if (!lostPackets.isEmpty())
                congestionController.onPacketsLost(lostPackets, lostLength, rttData);
        }
    }

    private void processLossDetection(List<Tracker> trackers)
    {
        long lostLength = 0;
        List<Packet.WithFrames> lostPackets = new ArrayList<>();
        for (Tracker tracker : trackers)
        {
            tracker.lossDetectionTask = null;
            lostLength += tracker.detectLostPackets(rttData, lostPackets);
        }
        if (!lostPackets.isEmpty())
            congestionController.onPacketsLost(lostPackets, lostLength, rttData);
    }

    @Override
    protected Action process() throws Throwable
    {
        List<PacketEntry> packets;
        List<AckEntry> acks;
        List<Tracker> trackers;
        try (var _ = lock.lock())
        {
            packets = new ArrayList<>(packetEntries);
            packetEntries.clear();
            acks = new ArrayList<>(ackEntries);
            ackEntries.clear();
            trackers = new ArrayList<>(lossTrackers);
            lossTrackers.clear();
        }

        processPacketsSent(packets);

        processAckFramesReceived(acks);

        processLossDetection(trackers);

        return Action.IDLE;
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
        long lossDelay = (long)(getTimeReorderingFactor() * Math.max(rttData.latestRTT(), rttData.smoothedRTT()));
        return Math.max(lossDelay, getSchedulerResolution());
    }

    @Override
    public String toString()
    {
        return "%s@%x%s".formatted(TypeUtil.toShortName(getClass()), hashCode(), trackers.values());
    }

    private class Tracker implements Runnable
    {
        private final Map<Long, PacketEntry> entries = new LinkedHashMap<>();
        private final PacketNumberSpace packetNumberSpace;
        private Scheduler.Task lossDetectionTask;
        private PacketEntry largestAckedEntry;

        private Tracker(PacketNumberSpace packetNumberSpace)
        {
            this.packetNumberSpace = packetNumberSpace;
        }

        private void put(long packetNumber, PacketEntry entry)
        {
            entries.put(packetNumber, entry);
        }

        private PacketEntry remove(long packetNumber)
        {
            return entries.remove(packetNumber);
        }

        private long acknowledgePackets(AckFrame frame, List<Packet.WithFrames> output)
        {
            long[] acknowledged = frame.allAcknowledged();
            long ackedLength = 0;
            for (int i = 0; i < acknowledged.length; i++)
            {
                long packetNumber = acknowledged[i];
                PacketEntry entry = remove(packetNumber);
                if (i == 0)
                    largestAckedEntry = entry;
                if (entry != null)
                {
                    ackedLength += entry.length();
                    output.add(entry.packet());
                }
            }
            return ackedLength;
        }

        private long detectLostPackets(RTTData rttData, List<Packet.WithFrames> output)
        {
            // RFC-9002[A.10].

            long lossDelay = calculatePacketLossDelay(rttData);

            long lostLength = 0;
            long largestAckedPacketNumber = largestAckedEntry == null ? -1 : largestAckedEntry.packet().packetNumber();

            long lossDetectionDelay = lossDelay;
            Iterator<PacketEntry> iterator = entries.values().iterator();
            while (iterator.hasNext())
            {
                PacketEntry entry = iterator.next();

                // Detect loss by packet number.
                if (largestAckedPacketNumber >= 0)
                {
                    long packetNumber = entry.packet().packetNumber();

                    // If the packet is in-flight, it is not lost.
                    if (packetNumber > largestAckedPacketNumber)
                        break;

                    // Assumes the sender does not introduce packet number gaps.
                    if (largestAckedPacketNumber > packetNumber + getPacketReorderingThreshold())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("packet lost by number {}>{}+{} {} on {}", largestAckedPacketNumber, packetNumber, getPacketReorderingThreshold(), entry.packet(), this);
                        lostLength += entry.length();
                        output.add(entry.packet());
                        iterator.remove();
                        continue;
                    }
                }

                // Detect loss by send time.
                long sentDelay = NanoTime.since(entry.sendNanoTime());
                if (sentDelay > lossDelay)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("packet lost by time sentDelay={}us lossDelay={}us {} on {}", TimeUnit.NANOSECONDS.toMicros(sentDelay), TimeUnit.NANOSECONDS.toMicros(lossDelay), entry.packet(), this);
                    lostLength += entry.length();
                    output.add(entry.packet());
                    iterator.remove();
                    continue;
                }

                // The packet is in-flight, calculate the earliest
                // time to schedule loss detection by send time.
                if (lossDetectionTask == null)
                    lossDetectionDelay = Math.min(lossDetectionDelay, sentDelay);
            }

            // Only schedule loss detection if there are packets in-flight.
            if (!entries.isEmpty())
                lossDetectionTask = scheduler.schedule(this, lossDetectionDelay, TimeUnit.NANOSECONDS);

            return lostLength;
        }

        private void schedulePacketLossDetection(RTTData rttData)
        {
            if (lossDetectionTask == null)
                lossDetectionTask = scheduler.schedule(this, calculatePacketLossDelay(rttData), TimeUnit.NANOSECONDS);
        }

        @Override
        public void run()
        {
            try(var _ = lock.lock())
            {
                lossTrackers.add(this);
            }
            iterate();
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), packetNumberSpace, entries.keySet());
        }
    }

    private record PacketEntry(PacketNumberSpace space, Packet.WithFrames packet, long length, long sendNanoTime)
    {
        @Override
        public String toString()
        {
            return "%s@%x[%s,#%d,length=%d]".formatted(TypeUtil.toShortName(getClass()), hashCode(), space, packet.packetNumber(), length);
        }
    }

    private record AckEntry(EncryptionLevel encryptionLevel, AckFrame frame)
    {
    }
}
