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
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.PingFrame;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class PacketTrackerTest
{
    @Test
    public void testSendPacketNoAckPTOFires()
    {
        TestScheduler scheduler = new TestScheduler();
        CongestionController cc = new TestCongestionController();
        PacketTracker packetTracker = new TestPacketTracker(scheduler, cc);

        // Send packet.
        Packet packet = new InitialPacket(QuicVersion.V1, new byte[0], new byte[0], new byte[0], 0, List.of(PingFrame.INSTANCE));
        EncryptionLevel encryptionLevel = EncryptionLevel.from(packet);
        packetTracker.processPacketSent(null, packet, 1024, false);

        // PTO scheduled.
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(true));

        // Simulate that the PTO fires.
        scheduler.task.runnable().run();

        assertEquals(1, packetTracker.getProbeTimeoutBackoff());
    }

    @Test
    public void testSendPacketNoAckPTOFiresProbeSentPTOBackoffIncreased()
    {
        TestScheduler scheduler = new TestScheduler();
        CongestionController cc = new TestCongestionController();
        PacketTracker packetTracker = new TestPacketTracker(scheduler, cc);

        // Send packet.
        Packet.WithFrames packet = new OneRTTPacket(0, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        EncryptionLevel encryptionLevel = EncryptionLevel.from(packet);
        packetTracker.processPacketSent(null, packet, 1024, false);

        // PTO scheduled.
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(true));

        // Simulate that the PTO fires.
        scheduler.task.runnable().run();

        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(false));
        assertEquals(1, packetTracker.getProbeTimeoutBackoff());

        // Send the probe.
        Packet.WithFrames probe = new OneRTTPacket(1, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        packetTracker.processPacketSent(null, probe, 128, false);

        // PTO scheduled.
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(true));

        // Simulate that the PTO fires.
        scheduler.task.runnable().run();

        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(false));
        assertEquals(2, packetTracker.getProbeTimeoutBackoff());
    }

    @Test
    public void testSendPacketReceiveAckPTOCancelled()
    {
        TestScheduler scheduler = new TestScheduler();
        CongestionController cc = new TestCongestionController();
        PacketTracker packetTracker = new TestPacketTracker(scheduler, cc);

        // Send packet.
        Packet.WithFrames packet = new OneRTTPacket(0, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        EncryptionLevel encryptionLevel = EncryptionLevel.from(packet);
        packetTracker.processPacketSent(null, packet, 1024, false);

        // PTO scheduled.
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(true));

        // Receive the ack.
        packetTracker.processAckFrameReceived(null, encryptionLevel, new AckFrame(packet.packetNumber(), 0, 0, List.of()));

        // PTO cancelled.
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(false));

        assertEquals(0, packetTracker.getProbeTimeoutBackoff());

        // System is fully acked, no timeouts.
        assertThat(packetTracker.hasLossTimeoutTask(encryptionLevel), is(false));
    }

    @Test
    public void testSendTwoPacketsReceiveAckTwoLTOFires() throws Exception
    {
        TestScheduler scheduler = new TestScheduler();
        TestCongestionController cc = new TestCongestionController();
        PacketTracker packetTracker = new TestPacketTracker(scheduler, cc);

        // Send and ack packet 1 to warm up.
        Packet.WithFrames packet1 = new OneRTTPacket(1, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        EncryptionLevel encryptionLevel = EncryptionLevel.from(packet1);
        packetTracker.processPacketSent(null, packet1, 512, false);
        packetTracker.processAckFrameReceived(null, encryptionLevel, new AckFrame(packet1.packetNumber(), 0, 0, List.of()));

        // Send packet 2.
        Packet.WithFrames packet2 = new OneRTTPacket(2, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        packetTracker.processPacketSent(null, packet2, 1024, false);

        // Send packet 3.
        Packet.WithFrames packet3 = new OneRTTPacket(3, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        packetTracker.processPacketSent(null, packet3, 768, false);

        // Simulate round-trip time.
        Thread.sleep(100);

        // Receive the ack for packet 3.
        packetTracker.processAckFrameReceived(null, encryptionLevel, new AckFrame(packet3.packetNumber(), 0, 0, List.of()));

        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(false));
        // LTO scheduled for packet 2.
        assertThat(packetTracker.hasLossTimeoutTask(encryptionLevel), is(true));

        assertNotNull(scheduler.task);
        // Simulate that the LTO fires.
        TimeUnit.NANOSECONDS.sleep(2 * scheduler.task.delayNanos());
        scheduler.task.runnable().run();

        // Packet 2 is lost.
        assertThat(cc.lost.size(), is(1));
        assertThat(cc.lost.getLast().packetNumber(), is(2L));
    }

    @Test
    public void testSendTwoPacketsReceiveAckTwoSendPacketLTOFiresThenPTOScheduled() throws Exception
    {
        TestScheduler scheduler = new TestScheduler();
        TestCongestionController cc = new TestCongestionController();
        PacketTracker packetTracker = new TestPacketTracker(scheduler, cc);

        // Send and ack packet 1 to warm up.
        Packet.WithFrames packet1 = new OneRTTPacket(1, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        EncryptionLevel encryptionLevel = EncryptionLevel.from(packet1);
        packetTracker.processPacketSent(null, packet1, 512, false);
        packetTracker.processAckFrameReceived(null, encryptionLevel, new AckFrame(packet1.packetNumber(), 0, 0, List.of()));

        // Send packet 2.
        Packet.WithFrames packet2 = new OneRTTPacket(2, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        packetTracker.processPacketSent(null, packet2, 1024, false);

        // Send packet 3.
        Packet.WithFrames packet3 = new OneRTTPacket(3, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        packetTracker.processPacketSent(null, packet3, 768, false);

        // Simulate round-trip time.
        Thread.sleep(100);

        // Receive the ack for packet 3.
        packetTracker.processAckFrameReceived(null, encryptionLevel, new AckFrame(packet3.packetNumber(), 0, 0, List.of()));

        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(false));
        // LTO scheduled for packet 2.
        assertThat(packetTracker.hasLossTimeoutTask(encryptionLevel), is(true));

        // Send packet 4.
        Packet.WithFrames packet4 = new OneRTTPacket(4, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        packetTracker.processPacketSent(null, packet4, 256, false);

        // PTO not scheduled for packet 4 because LTO is active.
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(false));

        assertNotNull(scheduler.task);
        // Simulate that the LTO fires.
        TimeUnit.NANOSECONDS.sleep(2 * scheduler.task.delayNanos());
        scheduler.task.runnable().run();

        // Packet 2 is lost.
        assertThat(cc.lost.size(), is(1));
        assertThat(cc.lost.getLast().packetNumber(), is(2L));

        // No LTO for packet 4, as there is no evidence of loss for it.
        assertThat(packetTracker.hasLossTimeoutTask(encryptionLevel), is(false));
        // PTO has been scheduled for packet 4.
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(true));
    }

    @Test
    public void sendManyPacketsReceiveOnlyAckLastLTOFires() throws Exception
    {
        TestScheduler scheduler = new TestScheduler();
        TestCongestionController cc = new TestCongestionController();
        PacketTracker packetTracker = new TestPacketTracker(scheduler, cc);

        // Send and ack packet 1 to warm up.
        Packet.WithFrames packet1 = new OneRTTPacket(1, new byte[0], false, false, List.of(PingFrame.INSTANCE));
        EncryptionLevel encryptionLevel = EncryptionLevel.from(packet1);
        packetTracker.processPacketSent(null, packet1, 512, false);
        packetTracker.processAckFrameReceived(null, encryptionLevel, new AckFrame(packet1.packetNumber(), 0, 0, List.of()));

        long packetNumberBegin = packet1.packetNumber() + 1;
        long packetNumberEnd = packetNumberBegin + packetTracker.getPacketReorderingThreshold();
        for (long packetNumber = packetNumberBegin; packetNumber <= packetNumberEnd; ++packetNumber)
        {
            Packet.WithFrames packet = new OneRTTPacket(packetNumber, new byte[0], false, false, List.of(PingFrame.INSTANCE));
            packetTracker.processPacketSent(null, packet, 512 + packetNumber, false);
        }

        // Simulate round-trip time.
        Thread.sleep(100);

        // Receive the ack for the last packet.
        packetTracker.processAckFrameReceived(null, encryptionLevel, new AckFrame(packetNumberEnd, 0, 0, List.of()));

        // Packet 2 is lost by number.
        assertThat(cc.lost.size(), is(1));
        assertThat(cc.lost.getLast().packetNumber(), is(2L));
        cc.lost.clear();

        // LTO scheduled.
        assertThat(packetTracker.hasLossTimeoutTask(encryptionLevel), is(true));
        // PTO not scheduled because LTO is active.
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(false));

        assertNotNull(scheduler.task);
        // Wait enough to lose all the other packets by time.
        TimeUnit.NANOSECONDS.sleep(2 * scheduler.task.delayNanos());
        // Simulate that the LTO fires.
        scheduler.task.runnable().run();

        // Packets are lost by time.
        assertThat(cc.lost.size(), is(2));
        assertThat(cc.lost.getLast().packetNumber(), is(packetNumberEnd - 1));

        // System is idle, no timeouts.
        assertThat(packetTracker.hasLossTimeoutTask(encryptionLevel), is(false));
        assertThat(packetTracker.hasProbeTimeoutTask(encryptionLevel), is(false));
    }

    private static class TestPacketTracker extends PacketTracker
    {
        private TestPacketTracker(Scheduler scheduler, CongestionController congestionController)
        {
            super(scheduler, congestionController);
        }

        @Override
        void onTaskTimeout(QuicSession session, Runnable task)
        {
            task.run();
        }

        @Override
        void sendProbe(QuicSession session, EncryptionLevel encryptionLevel)
        {
        }

        @Override
        void retransmit(QuicSession session, List<Packet.WithFrames> lostPackets)
        {
        }
    }

    private record Task(Runnable runnable, long delayNanos)
    {
    }

    private static class TestScheduler extends AbstractLifeCycle implements Scheduler
    {
        private PacketTrackerTest.Task task;

        @Override
        public Task schedule(Runnable job, long delay, TimeUnit units)
        {
            task = new PacketTrackerTest.Task(job, units.toNanos(delay));
            return () ->
            {
                task = null;
                return true;
            };
        }
    }

    private static class TestCongestionController implements CongestionController
    {
        private final List<Packet.WithFrames> lost = new ArrayList<>();

        @Override
        public void onPacketSent(Packet.WithFrames packet, long length, boolean dataStalled, RTTData rttData)
        {
        }

        @Override
        public void onPacketsAcknowledged(List<Packet.WithFrames> packets, RTTData rttData)
        {
        }

        @Override
        public void onPacketsLost(List<Packet.WithFrames> packets, RTTData rttData)
        {
            lost.addAll(packets);
        }

        @Override
        public void onIdle()
        {
        }

        @Override
        public long getAvailableWindow()
        {
            return 0;
        }

        @Override
        public long getCongestionWindow()
        {
            return 10 * 1024;
        }

        @Override
        public long getPacingDelay()
        {
            return 0;
        }
    }
}
