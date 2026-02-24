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
import org.eclipse.jetty.quic.common.packets.EncodedPacketNumber;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitialPacketGenerator implements PacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(InitialPacketGenerator.class);

    private final PacketNumbers packetNumbers;
    private final FramesGenerator framesGenerator;
    private final Encrypter encrypter;
    private int payloadMinLength;

    public InitialPacketGenerator(PacketNumbers packetNumbers, FramesGenerator framesGenerator, Encrypter encrypter)
    {
        this.packetNumbers = packetNumbers;
        this.framesGenerator = framesGenerator;
        this.encrypter = encrypter;
        // RFC-9000[14.1]: UDP payload must be at least 1200 bytes.
        // The minimum InitialPacket header length is 11, considering
        // empty connection IDs and empty token; the AEAD tag is 16,
        // so 1200 - 11 - 16 = 1173 bytes.
        this.payloadMinLength = 1173;
    }

    public int getPayloadMinimumLength()
    {
        return payloadMinLength;
    }

    public void setPayloadMinimumLength(int payloadMinLength)
    {
        this.payloadMinLength = payloadMinLength;
    }

    @Override
    public void generate(RetainableByteBuffer.Mutable packetAccumulator, Packet packet, RetainableByteBuffer.Mutable framesAccumulator) throws Exception
    {
        generate(packetAccumulator, (InitialPacket)packet, framesAccumulator);
    }

    private void generate(RetainableByteBuffer.Mutable packetAccumulator, InitialPacket packet, RetainableByteBuffer.Mutable framesAccumulator) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        boolean releaseFramesAccumulator = false;
        if (framesAccumulator == null)
        {
            releaseFramesAccumulator = true;
            RetainableByteBuffer.Mutable payloadAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
            packet.frames().forEach(frame -> framesGenerator.generateFrame(payloadAccumulator, frame, Integer.MAX_VALUE));
            if (LOG.isDebugEnabled())
                LOG.debug("generated {} frame bytes for {}", payloadAccumulator.size(), packet);
            framesAccumulator = payloadAccumulator;
        }

        long framesLength = framesAccumulator.size();
        int payloadMinLength = getPayloadMinimumLength();
        if (framesLength < payloadMinLength)
        {
            // A PADDING frame is just the byte 0x00.
            long paddingLength = payloadMinLength - framesLength;
            byte[] padding = new byte[Math.toIntExact(paddingLength)];
            framesAccumulator.put(padding);
            if (LOG.isDebugEnabled())
                LOG.debug("generated {} padding bytes for {}", paddingLength, packet);
        }

        RetainableByteBuffer.Mutable headerAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        int form = 0b11000000;
        int type = packet.packetType().type(packet.quicVersion()) << 4;
        long packetNumber = packet.packetNumber();
        EncodedPacketNumber encodedPacketNumber = packetNumbers.encode(EncryptionLevel.INITIAL, packetNumber);
        int msb = form | type | (encodedPacketNumber.length() - 1);
        headerAccumulator.put((byte)msb);

        headerAccumulator.putInt(packet.quicVersion().code());

        byte[] dstConnectionId = packet.destinationConnectionId();
        headerAccumulator.put((byte)dstConnectionId.length);
        headerAccumulator.put(dstConnectionId);

        byte[] srcConnectionId = packet.sourceConnectionId();
        headerAccumulator.put((byte)srcConnectionId.length);
        headerAccumulator.put(srcConnectionId);

        byte[] token = packet.token();
        VarLenInt.encode(headerAccumulator, token == null ? 0 : token.length);
        if (token != null)
            headerAccumulator.put(token);

        // AEAD encryption produces 16 additional bytes.
        long encryptedFramesLength = framesAccumulator.size() + 16;
        long packetLength = encodedPacketNumber.length() + encryptedFramesLength;

        VarLenInt.encode(headerAccumulator, packetLength);

        encodedPacketNumber.putTo(headerAccumulator);

        PacketBuffers packetBuffers = encrypter.encrypt(EncryptionLevel.INITIAL, packetNumber, headerAccumulator, framesAccumulator);

        if (LOG.isDebugEnabled())
            LOG.debug("encrypted {} {}", packet, packetBuffers);

        headerAccumulator.release();
        if (releaseFramesAccumulator)
            framesAccumulator.release();

        packetAccumulator.add(packetBuffers.header());
        packetAccumulator.add(packetBuffers.payload());
    }
}
