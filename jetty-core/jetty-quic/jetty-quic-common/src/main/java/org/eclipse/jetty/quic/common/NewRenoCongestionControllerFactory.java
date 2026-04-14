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

    private static class NewRenoCongestionController implements CongestionController
    {
        private static final Logger LOG = LoggerFactory.getLogger(NewRenoCongestionController.class);

        private final Map<Long, Long> packets = new HashMap<>();
        private final NewRenoCongestionControllerFactory factory;
        private final long minInFlight;
        private State state = State.SLOW_START;
        private long inFlight;
        private long maxInFlight;
        private long slowStart;
        private long rtt;
        private long earliestSendNanoTime;
        private long latestSendNanoTime;
        private long latestSendBytes;
        private long congestionRecoveryNanoTime;

        private NewRenoCongestionController(NewRenoCongestionControllerFactory factory)
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
        public void onPacketSent(Packet.WithFrames packet, long length, RTTData rttData)
        {
            long sendNanoTime = NanoTime.now();
            if (sendNanoTime == 0)
                sendNanoTime = 1;
            packets.put(packet.packetNumber(), sendNanoTime);

            // RFC-9002[B.4].
            inFlight += length;
            rtt = rttData.smoothedRTT();
            latestSendNanoTime = sendNanoTime;
            latestSendBytes = length;

            if (LOG.isDebugEnabled())
                LOG.debug("sent [{}] on {}", packet.packetNumber(), this);
        }

        @Override
        public void onPacketsAcknowledged(List<Packet.WithFrames> acked, long totalLength, RTTData rttData)
        {
            boolean exitRecovery = false;
            for (Packet.WithFrames packet : acked)
            {
                long sendNanoTime = packets.remove(packet.packetNumber());
                // RFC-9002[7.3.2]: exit congestion recovery when a packet
                // sent during the congestion recovery period is acknowledged.
                if (state == State.CONGESTION_RECOVERY && !exitRecovery)
                    exitRecovery = NanoTime.isBefore(congestionRecoveryNanoTime, sendNanoTime);
            }

            inFlight -= totalLength;
            rtt = rttData.smoothedRTT();

            switch (state)
            {
                case SLOW_START -> maxInFlight += totalLength;
                case CONGESTION_AVOIDANCE -> maxInFlight += (factory.udpPayloadMaxLength * totalLength) / maxInFlight;
                case CONGESTION_RECOVERY ->
                {
                    if (exitRecovery)
                    {
                        state = State.CONGESTION_AVOIDANCE;
                        earliestSendNanoTime = 0;
                        congestionRecoveryNanoTime = 0;
                        if (LOG.isDebugEnabled())
                            LOG.debug("exiting congestion recovery on {}", this);
                    }
                }
            }

            if (LOG.isDebugEnabled())
                LOG.debug("acked {} on {}", acked.stream().map(Packet.WithFrames::packetNumber).toList(), this);
        }

        @Override
        public void onPacketsLost(List<Packet.WithFrames> lost, long totalLength, RTTData rttData)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("lost {} on {}", lost.stream().map(Packet.WithFrames::packetNumber).toList(), this);

            long minSendNanoTime = lost.stream()
                .mapToLong(Packet.WithFrames::packetNumber)
                .map(packets::remove)
                .min()
                .orElse(0L);

            inFlight -= totalLength;

            if (state == State.SLOW_START && isPersistentCongestion(rttData))
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
                    state = State.SLOW_START;
                    // Exit congestion recovery.
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
            earliestSendNanoTime = minSendNanoTime;
            congestionRecoveryNanoTime = NanoTime.now();
            if (congestionRecoveryNanoTime == 0)
                congestionRecoveryNanoTime = 1;
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
            long delay = latestSendBytes * rtt / maxInFlight;
            long elapsed = NanoTime.since(latestSendNanoTime);
            return Math.max(0, delay - elapsed);
        }

        private boolean isPersistentCongestion(RTTData rttData)
        {
            long persistentCongestionDuration = (rttData.smoothedRTT() + 4 * rttData.variationRTT() + factory.ackMaxDelay) * 3;
            return earliestSendNanoTime != 0 && NanoTime.since(earliestSendNanoTime) > persistentCongestionDuration;
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
    }
}
