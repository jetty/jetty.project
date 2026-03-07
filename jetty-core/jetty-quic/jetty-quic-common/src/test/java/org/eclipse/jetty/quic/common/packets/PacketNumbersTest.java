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

package org.eclipse.jetty.quic.common.packets;

import java.util.List;

import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PacketNumbersTest
{
    @Test
    public void testPacketNumberDecodingServerSendsManyPacketsClientSendsFewPackets()
    {
        PacketNumbers serverPacketNumbers = new PacketNumbers();
        PacketNumbers clientPacketNumbers = new PacketNumbers();

        // Server sends packets 0-299 and the client receives them.
        // The client ACKs server packets, so the server's largest
        // acknowledged packet number stays close to the next packet number.
        // But the client sends very few packets (just the initial request),
        // so the client's largest acknowledged stays close to 0.
        for (long serverPacketNumber = 0; serverPacketNumber < 300; ++serverPacketNumber)
        {
            // Server encodes the packet number.
            EncodedPacketNumber encoded = serverPacketNumbers.encode(EncryptionLevel.ONE_RTT, serverPacketNumber);

            // Client decodes the packet number.
            long decoded = clientPacketNumbers.decode(EncryptionLevel.ONE_RTT, encoded);
            // Simulate the client successfully receiving the packet.
            OneRTTPacket receivedPacket = new OneRTTPacket(decoded, new byte[0], false, false, List.of());
            clientPacketNumbers.onPacketReceived(receivedPacket);

            assertEquals(serverPacketNumber, decoded);

            // Simulate the client ACKing server packets.
            AckFrame ackFrame = new AckFrame(serverPacketNumber, 0, 0, List.of());
            serverPacketNumbers.onAckFrameReceived(EncryptionLevel.ONE_RTT, ackFrame);

            // Here we should simulate the server ACKing client packets.
            // But for this test we do not ACK for simplicity, so that
            // we can verify that the decoding works correctly.
        }
    }
}
