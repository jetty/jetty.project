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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.frames.FrameGenerator;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.internal.EncryptionLevel;
import org.eclipse.jetty.quic.common.internal.PacketBuffers;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitialPacketGenerator implements PacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(InitialPacketGenerator.class);

    private final PacketNumbers packetNumbers;
    private final FrameGenerator frameGenerator;
    private final Encrypter encrypter;
    private int payloadMinLength;

    public InitialPacketGenerator(PacketNumbers packetNumbers, FrameGenerator frameGenerator, Encrypter encrypter)
    {
        this.packetNumbers = packetNumbers;
        this.frameGenerator = frameGenerator;
        this.encrypter = encrypter;
        // RFC 9000, 14.1: UDP payload must be at least 1200 bytes.
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
    public void generate(RetainableByteBuffer.Mutable accumulator, Packet packet) throws Exception
    {
        generate(accumulator, (InitialPacket)packet);
    }

    private void generate(RetainableByteBuffer.Mutable accumulator, InitialPacket packet) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        // TODO: fix these deprecations.
        ByteBufferPool.Accumulator frameAccumulator = new ByteBufferPool.Accumulator();
        packet.getFrames().forEach(frame -> frameGenerator.generate(frameAccumulator, frame));
        RetainableByteBuffer.Mutable encodedFrames = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        frameAccumulator.getByteBuffers().forEach(encodedFrames::add);

        if (LOG.isDebugEnabled())
            LOG.debug("generated {} frame bytes for {}", encodedFrames.size(), packet);

        // TODO: handle the case where framesLength is bigger than maxUDPPayloadSize?
        //  Although we have not received it yet from the other peer.
        //  See RFC 9000, 14 and 18.2.

        long framesLength = encodedFrames.size();
        int payloadMinLength = getPayloadMinimumLength();
        if (framesLength < payloadMinLength)
        {
            // A PADDING frame is just the byte 0x00.
            long paddingLength = payloadMinLength - framesLength;
            byte[] padding = new byte[Math.toIntExact(paddingLength)];
            encodedFrames.put(padding);
            if (LOG.isDebugEnabled())
                LOG.debug("generated {} padding bytes for {}", paddingLength, packet);
        }

        RetainableByteBuffer.Mutable headerAccumulator = new RetainableByteBuffer.DynamicCapacity(null, true, -1, 0, 0);
        int form = 0b11000000;
        int type = packet.getPacketType().type(packet.getVersion()) << 4;
        long packetNumber = packet.getPacketNumber();
        EncodedPacketNumber encodedPacketNumber = packetNumbers.encode(EncryptionLevel.INITIAL, packetNumber);
        int msb = form | type | (encodedPacketNumber.length() - 1);
        headerAccumulator.put((byte)msb);

        headerAccumulator.putInt(packet.getVersion().code());

        byte[] dstConnectionId = packet.getDestinationConnectionId();
        headerAccumulator.put((byte)dstConnectionId.length);
        headerAccumulator.put(dstConnectionId);

        byte[] srcConnectionId = packet.getSourceConnectionId();
        headerAccumulator.put((byte)srcConnectionId.length);
        headerAccumulator.put(srcConnectionId);

        byte[] token = packet.getToken();
        VarLenInt.encode(headerAccumulator, token.length);
        headerAccumulator.put(token);

        // AEAD encryption produces 16 additional bytes.
        long encryptedFramesLength = encodedFrames.size() + 16;
        long packetLength = encodedPacketNumber.length() + encryptedFramesLength;

        VarLenInt.encode(headerAccumulator, packetLength);

        encodedPacketNumber.putTo(headerAccumulator);

        PacketBuffers packetBuffers = encrypter.encrypt(EncryptionLevel.INITIAL, packetNumber, headerAccumulator.getByteBuffer(), encodedFrames.getByteBuffer());

        if (LOG.isDebugEnabled())
            LOG.debug("encrypted {} {}", packet, packetBuffers);

        accumulator.add(packetBuffers.header());
        accumulator.add(packetBuffers.payload());
    }
}
