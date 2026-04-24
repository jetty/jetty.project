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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NewRenoCongestionControllerFactory implements CongestionController.Factory
{
    private final long udpPayloadMaxLength;
    private final long ackMaxDelay;

    public NewRenoCongestionControllerFactory()
    {
        // RFC-9000[14.1]: the smallest UDP payload length is 1200 bytes.
        this(1200, TimeUnit.MILLISECONDS.toNanos(25));
    }

    public NewRenoCongestionControllerFactory(long udpPayloadMaxLength, long acknowledgmentMaxDelay)
    {
        this.udpPayloadMaxLength = udpPayloadMaxLength;
        this.ackMaxDelay = acknowledgmentMaxDelay;
    }

    @Override
    public CongestionController newCongestionController()
    {
        return new NewRenoCongestionController(this);
    }

    protected static class NewRenoCongestionController implements CongestionController
    {
        private static final Logger LOG = LoggerFactory.getLogger(NewRenoCongestionController.class);

        private final Map<Long, Entry> packets = new HashMap<>();
        private final NewRenoCongestionControllerFactory factory;
        private final long minInFlight;
        private State state = State.SLOW_START;
        private long inFlight;
        private long maxInFlight;
        private long slowStart;
        private long rtt;
        private long latestSendNanoTime;
        private long latestSendBytes;
        private long congestionRecoveryNanoTime;
        private boolean persistentCongestion;

        public NewRenoCongestionController(NewRenoCongestionControllerFactory factory)
        {
            this.factory = factory;
            // RFC-9002[7.2]: minimum window.
            this.minInFlight = 2L * factory.udpPayloadMaxLength;
            // RFC-9002[7.2]: initial window.
            long initialWindow = Math.min(10L * factory.udpPayloadMaxLength, Math.max(2L * factory.udpPayloadMaxLength, 14720));
            // RFC-9002[B.3]: initialization.
            this.maxInFlight = initialWindow;
            this.slowStart = Long.MAX_VALUE;
        }

        @Override
        public void onPacketSent(Packet.WithFrames packet, long length, boolean dataStalled, RTTData rttData)
        {
            long sendNanoTime = NanoTime.now();
            packets.put(packet.packetNumber(), new Entry(length, dataStalled, sendNanoTime));

            // RFC-9002[B.4].
            inFlight += length;
            rtt = rttData.smoothedRTT();
            latestSendNanoTime = sendNanoTime;
            latestSendBytes = length;

            if (LOG.isDebugEnabled())
                LOG.debug("sent [{}] length={} dataStalled={} on {}", packet.packetNumber(), length, dataStalled, this);
        }

        @Override
        public void onPacketsAcknowledged(List<Packet.WithFrames> acked, RTTData rttData)
        {
            for (Packet.WithFrames packet : acked)
            {
                Entry entry = packets.remove(packet.packetNumber());
                if (entry == null)
                    continue;

                inFlight = Math.max(0, inFlight - entry.length());

                switch (state)
                {
                    case SLOW_START ->
                    {
                        if (!entry.dataStalled())
                            maxInFlight += entry.length();
                        if (maxInFlight >= slowStart)
                        {
                            // RFC-9002[B.5]: exit slow start.
                            state = State.CONGESTION_AVOIDANCE;
                            if (LOG.isDebugEnabled())
                                LOG.debug("exiting slow start on {}", this);
                        }
                    }
                    case CONGESTION_AVOIDANCE ->
                    {
                        if (!entry.dataStalled())
                            maxInFlight += (factory.udpPayloadMaxLength * entry.length()) / maxInFlight;
                    }
                    case CONGESTION_RECOVERY ->
                    {
                        // RFC-9002[7.3.2]: exit congestion recovery when a packet
                        // sent during the congestion recovery period is acknowledged.
                        if (NanoTime.isBefore(congestionRecoveryNanoTime, entry.sendNanoTime()))
                        {
                            state = State.CONGESTION_AVOIDANCE;
                            congestionRecoveryNanoTime = 0;
                            if (!entry.dataStalled())
                                maxInFlight += (factory.udpPayloadMaxLength * entry.length()) / maxInFlight;
                            if (LOG.isDebugEnabled())
                                LOG.debug("exiting congestion recovery on {}", this);
                        }
                    }
                }
            }

            persistentCongestion = false;
            rtt = rttData.smoothedRTT();

            if (LOG.isDebugEnabled())
                LOG.debug("acked {} on {}", acked.stream().map(Packet.WithFrames::packetNumber).toList(), this);
        }

        @Override
        public void onPacketsLost(List<Packet.WithFrames> lost, RTTData rttData)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("lost {} on {}", lost.stream().map(Packet.WithFrames::packetNumber).toList(), this);

            for (Packet.WithFrames packet : lost)
            {
                Entry entry = packets.remove(packet.packetNumber());
                if (entry != null)
                    inFlight = Math.max(0, inFlight - entry.length());
            }

            rtt = rttData.smoothedRTT();

            if (state == State.SLOW_START && persistentCongestion)
            {
                // In slow start from congestion recovery,
                // but still in persistent congestion.
                if (LOG.isDebugEnabled())
                    LOG.debug("persistent congestion on {}", this);
                return;
            }

            if (state == State.CONGESTION_RECOVERY)
            {
                // If there is persistent congestion, move to slow start.
                if (isPersistentCongestion(rttData))
                {
                    // Exit congestion recovery.
                    state = State.SLOW_START;
                    persistentCongestion = true;
                    congestionRecoveryNanoTime = 0;
                    maxInFlight = minInFlight;

                    if (LOG.isDebugEnabled())
                        LOG.debug("persistent congestion detected on {}", this);
                }
                return;
            }

            // Either in slow start or congestion avoidance,
            // move to congestion recovery.
            state = State.CONGESTION_RECOVERY;
            congestionRecoveryNanoTime = NanoTime.now();
            slowStart = maxInFlight / 2;
            maxInFlight = Math.max(slowStart, minInFlight);

            if (LOG.isDebugEnabled())
                LOG.debug("entering congestion recovery on {}", this);
        }

        @Override
        public void onIdle()
        {
        }

        @Override
        public long getCongestionWindow()
        {
            return Math.max(0L, maxInFlight - inFlight);
        }

        @Override
        public long getPacingDelay()
        {
            if (rtt == 0)
                return 0;
            // RFC-9002[7.7]: pace a little faster using N=1.25=(5/4).
            long delay = (latestSendBytes * rtt * 4) / (maxInFlight * 5);
            long elapsed = NanoTime.since(latestSendNanoTime);
            return Math.max(0, delay - elapsed);
        }

        private boolean isPersistentCongestion(RTTData rttData)
        {
            long persistentCongestionDuration = (rttData.smoothedRTT() + 4 * rttData.variationRTT() + factory.ackMaxDelay) * 3;
            return NanoTime.since(congestionRecoveryNanoTime) > persistentCongestionDuration;
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s,cwnd/flight/max=%d/%d/%d]".formatted(TypeUtil.toShortName(getClass()), hashCode(), state, getCongestionWindow(), inFlight, maxInFlight);
        }

        private enum State
        {
            SLOW_START,
            CONGESTION_AVOIDANCE,
            CONGESTION_RECOVERY
        }

        private record Entry(long length, boolean dataStalled, long sendNanoTime)
        {
        }
    }
}
