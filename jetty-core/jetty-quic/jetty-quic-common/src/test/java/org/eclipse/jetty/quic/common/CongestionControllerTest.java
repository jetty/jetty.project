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

import java.util.List;

import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.PingFrame;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class CongestionControllerTest
{
    public static List<CongestionController> controllers()
    {
        return List.of(
            new NewRenoCongestionControllerFactory().newCongestionController()
        );
    }

    @ParameterizedTest
    @MethodSource("controllers")
    public void testDuplicateAck(CongestionController cc)
    {
        List<Frame> frames = List.of(new PingFrame());
        Packet.WithFrames packet = new OneRTTPacket(0, new byte[0], false, false, frames);
        RTTData rttData = new RTTData(0, 0, MILLISECONDS.toNanos(10), MILLISECONDS.toNanos(2));

        // Packet sent and acknowledged.
        cc.onPacketSent(packet, 1500, false, rttData);
        cc.onPacketsAcknowledged(List.of(packet), rttData);

        // Duplicate acknowledgement, no error.
        cc.onPacketsAcknowledged(List.of(packet), rttData);
    }
}
