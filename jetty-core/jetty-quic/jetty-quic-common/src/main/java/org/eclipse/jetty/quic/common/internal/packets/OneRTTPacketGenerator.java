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

package org.eclipse.jetty.quic.common.internal.packets;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.PacketBuffers;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.packets.EncodedPacketNumber;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OneRTTPacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(OneRTTPacketGenerator.class);

    private final PacketNumbers packetNumbers;
    private final Encrypter encrypter;

    public OneRTTPacketGenerator(PacketNumbers packetNumbers, Encrypter encrypter)
    {
        this.packetNumbers = packetNumbers;
        this.encrypter = encrypter;
    }

    public void generate(RetainableByteBuffer.Mutable packetAccumulator, OneRTTPacket packet, RetainableByteBuffer.Mutable framesAccumulator) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        RetainableByteBuffer.Mutable headerAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        int form = 0b01000000;
        int spin = packet.spin() ? 0b00100000 : 0b00000000;
        int keyPhase = packet.keyPhase() ? 0b00000100 : 0b00000000;
        long packetNumber = packet.packetNumber();
        EncodedPacketNumber encodedPacketNumber = packetNumbers.encode(EncryptionLevel.INITIAL, packetNumber);
        int msb = form | spin | keyPhase | (encodedPacketNumber.length() - 1);
        headerAccumulator.put((byte)msb);

        headerAccumulator.put(packet.destinationConnectionId());

        encodedPacketNumber.putTo(headerAccumulator);

        PacketBuffers packetBuffers = encrypter.encrypt(EncryptionLevel.ONE_RTT, packetNumber, headerAccumulator, framesAccumulator);

        if (LOG.isDebugEnabled())
            LOG.debug("encrypted {} {}", packet, packetBuffers);

        headerAccumulator.release();

        packetAccumulator.add(packetBuffers.header());
        packetAccumulator.add(packetBuffers.payload());
    }
}
