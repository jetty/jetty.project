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
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HandshakePacketGenerator implements PacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(HandshakePacketGenerator.class);

    private final PacketNumbers packetNumbers;
    private final FramesGenerator framesGenerator;
    private final Encrypter encrypter;

    public HandshakePacketGenerator(PacketNumbers packetNumbers, FramesGenerator framesGenerator, Encrypter encrypter)
    {
        this.packetNumbers = packetNumbers;
        this.framesGenerator = framesGenerator;
        this.encrypter = encrypter;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable accumulator, Packet packet) throws Exception
    {
        generate(accumulator, (HandshakePacket)packet);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, HandshakePacket packet) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        RetainableByteBuffer.Mutable payloadAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        packet.frames().forEach(frame -> framesGenerator.generate(payloadAccumulator, frame));

        if (LOG.isDebugEnabled())
            LOG.debug("generated {} frame bytes for {}", payloadAccumulator.size(), packet);

        // TODO: handle the case where framesLength is bigger than maxUDPPayloadSize?
        //  Although we have not received it yet from the other peer.
        //  See RFC 9000, 14 and 18.2.

        RetainableByteBuffer.Mutable headerAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        int form = 0b11000000;
        int type = packet.packetType().type(packet.quicVersion()) << 4;
        long packetNumber = packet.packetNumber();
        EncodedPacketNumber encodedPacketNumber = packetNumbers.encode(EncryptionLevel.HANDSHAKE, packetNumber);
        int msb = form | type | (encodedPacketNumber.length() - 1);
        headerAccumulator.put((byte)msb);

        headerAccumulator.putInt(packet.quicVersion().code());

        byte[] dstConnectionId = packet.destinationConnectionId();
        headerAccumulator.put((byte)dstConnectionId.length);
        headerAccumulator.put(dstConnectionId);

        byte[] srcConnectionId = packet.sourceConnectionId();
        headerAccumulator.put((byte)srcConnectionId.length);
        headerAccumulator.put(srcConnectionId);

        // AEAD encryption produces 16 additional bytes.
        long encryptedFramesLength = payloadAccumulator.size() + 16;
        long packetLength = encodedPacketNumber.length() + encryptedFramesLength;

        VarLenInt.encode(headerAccumulator, packetLength);

        encodedPacketNumber.putTo(headerAccumulator);

        PacketBuffers packetBuffers = encrypter.encrypt(EncryptionLevel.HANDSHAKE, packetNumber, headerAccumulator, payloadAccumulator);

        if (LOG.isDebugEnabled())
            LOG.debug("encrypted {} {}", packet, packetBuffers);

        headerAccumulator.release();
        payloadAccumulator.release();

        accumulator.add(packetBuffers.header());
        accumulator.add(packetBuffers.payload());
    }
}
