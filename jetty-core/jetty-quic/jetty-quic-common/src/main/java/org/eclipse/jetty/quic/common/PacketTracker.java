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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PacketTracker
{
    private static final Logger LOG = LoggerFactory.getLogger(PacketTracker.class);

    private final EnumMap<PacketNumberSpace, Tracker> trackers = new EnumMap<>(PacketNumberSpace.class);
    private final CongestionController congestionController;
    private long ackMaxDelay;
    private long ackDelayExponent;
    private long latestRTT;
    private long minimumRTT;
    private long smoothedRTT;
    private long variationRTT;

    public PacketTracker(CongestionController congestionController)
    {
        this.congestionController = congestionController;
        trackers.put(PacketNumberSpace.INITIAL, new Tracker());
        trackers.put(PacketNumberSpace.HANDSHAKE, new Tracker());
        trackers.put(PacketNumberSpace.APPLICATION, new Tracker());
        reset();
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

    public void reset()
    {
        // RFC-9002[A.4]: initialization.
        latestRTT = 0;
        minimumRTT = 0;
        smoothedRTT = TimeUnit.MILLISECONDS.toNanos(333);
        variationRTT = smoothedRTT / 2;
    }

    public void onPacketSent(Packet packet, long length)
    {
        // RFC-9002[A.5].

        // Packets without frames are not acknowledged, don't track them.
        if (!(packet instanceof Packet.WithFrames withFrames))
            return;

        if (!withFrames.requiresAcknowledgement())
            return;

        PacketNumberSpace space = PacketNumberSpace.from(EncryptionLevel.from(withFrames));
        Tracker tracker = this.trackers.get(space);
        tracker.put(withFrames.packetNumber(), new Entry(withFrames, length, NanoTime.now()));

        if (LOG.isDebugEnabled())
            LOG.debug("tracked packet {}[{}] length={} on {}", space, withFrames.packetNumber(), length, this);

        congestionController.onPacketSent(withFrames, length, new RTTData(latestRTT, minimumRTT, smoothedRTT, variationRTT));

        // TODO:
        //  2. start per-space timer if not already armed for time-based loss detection.
    }

    public void onAckFrameReceived(EncryptionLevel encryptionLevel, AckFrame frame)
    {
        // RFC-9002[A.7].

        PacketNumberSpace space = PacketNumberSpace.from(encryptionLevel);
        Tracker tracker = trackers.get(space);

        long[] acknowledged = frame.allAcknowledged();
        long totalLength = 0;
        Entry largestEntry = null;
        List<Packet.WithFrames> packets = new ArrayList<>(acknowledged.length);
        for (int i = 0; i < acknowledged.length; i++)
        {
            long packetNumber = acknowledged[i];
            Entry entry = tracker.remove(packetNumber);
            if (i == 0)
                largestEntry = entry;
            if (entry != null)
            {
                totalLength += entry.length();
                packets.add(entry.packet());
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("untracked packets {}#{} length={} on {}", space, acknowledged, totalLength, this);

        // TODO:
        //  detect lost packet by packet number.

        // TODO: this is possible in case of duplicate acks,
        //  but we should not be skipping notification to CC.
        if (largestEntry == null)
            return;

        boolean firstSample = latestRTT == 0;
        latestRTT = NanoTime.since(largestEntry.sendNanoTime());

        // RFC-9002[5.2]: minRTT must be initially set to the initial RTT,
        // and then updated with the lesser of minRTT and latestRTT.
        minimumRTT = Math.min(minimumRTT, latestRTT);

        if (firstSample)
        {
            smoothedRTT = latestRTT;
            variationRTT = latestRTT / 2;
        }
        else
        {
            // RFC-900[5.3]: rules for ackDelay.
            long ackDelay = -1;
            if (encryptionLevel == EncryptionLevel.ONE_RTT)
            {
                ackDelay = AckFrame.decodeAckDelay(frame.encodedAckDelay(), getAcknowledgmentDelayExponent());
                ackDelay = Math.min(ackDelay, getAcknowledgmentMaxDelay());
                if (ackDelay < minimumRTT)
                    ackDelay = 0;
            }

            // RFC-900[5.3]: calculate smoothedRTT and variationRTT.
            long adjustedRTT = latestRTT;
            if (latestRTT >= minimumRTT + ackDelay)
                adjustedRTT = latestRTT - ackDelay;
            smoothedRTT = (7 * smoothedRTT + adjustedRTT) / 8;
            long variation = Math.abs(smoothedRTT - adjustedRTT);
            variationRTT = (3 * variationRTT + variation) / 4;
        }

        congestionController.onPacketsAcknowledged(packets, totalLength, new RTTData(latestRTT, minimumRTT, smoothedRTT, variationRTT));
    }

    @Override
    public String toString()
    {
        return "%s@%x%s".formatted(TypeUtil.toShortName(getClass()), hashCode(), List.copyOf(trackers.entrySet()).reversed());
    }

    private static class Tracker
    {
        private final Map<Long, Entry> entries = new LinkedHashMap<>();

        public void put(long key, Entry entry)
        {
            entries.put(key, entry);
        }

        public Entry remove(long packetNumber)
        {
            return entries.remove(packetNumber);
        }

        @Override
        public String toString()
        {
            return "%s@%x%s".formatted(TypeUtil.toShortName(getClass()), hashCode(), entries.keySet());
        }
    }

    private record Entry(Packet.WithFrames packet, long length, long sendNanoTime)
    {
    }
}
