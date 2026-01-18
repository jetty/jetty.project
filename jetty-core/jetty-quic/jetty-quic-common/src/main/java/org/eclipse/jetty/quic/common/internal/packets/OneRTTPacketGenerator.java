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
import org.eclipse.jetty.quic.common.frames.FramesGenerator;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OneRTTPacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(OneRTTPacketGenerator.class);

    private final PacketNumbers packetNumbers;
    private final FramesGenerator framesGenerator;
    private final Encrypter encrypter;

    public OneRTTPacketGenerator(PacketNumbers packetNumbers, FramesGenerator framesGenerator, Encrypter encrypter)
    {
        this.packetNumbers = packetNumbers;
        this.framesGenerator = framesGenerator;
        this.encrypter = encrypter;
    }

    public void generate(RetainableByteBuffer.Mutable accumulator, OneRTTPacket packet) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        RetainableByteBuffer.Mutable payloadAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        packet.frames().forEach(frame -> framesGenerator.generate(payloadAccumulator, frame));

        if (LOG.isDebugEnabled())
            LOG.debug("generated {} frame bytes for {}", payloadAccumulator.size(), packet);

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

        PacketBuffers packetBuffers = encrypter.encrypt(EncryptionLevel.ONE_RTT, packetNumber, headerAccumulator, payloadAccumulator);

        if (LOG.isDebugEnabled())
            LOG.debug("encrypted {} {}", packet, packetBuffers);

        headerAccumulator.release();
        payloadAccumulator.release();

        accumulator.add(packetBuffers.header());
        accumulator.add(packetBuffers.payload());
    }
}
