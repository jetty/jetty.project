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

import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.packets.EncodedPacketNumber;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class InitialPacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(InitialPacketGenerator.class);

    private final PacketNumbers packetNumbers;
    private final Encrypter encrypter;
    private int payloadMinLength;

    public InitialPacketGenerator(PacketNumbers packetNumbers, Encrypter encrypter)
    {
        this.packetNumbers = packetNumbers;
        this.encrypter = encrypter;
        // RFC-9000 #14.1: UDP payload must be at least 1200 bytes.
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

    public void generate(InitialPacket packet, RetainableByteBuffer framesBuffer, RetainableByteBuffer.Mutable packetBuffer) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        long framesLength = framesBuffer.remaining();
        int payloadMinLength = getPayloadMinimumLength();
        if (framesLength < payloadMinLength)
        {
            // A PADDING frame is just the byte 0x00.
            int paddingLength = payloadMinLength - (int)framesLength;
            try (RetainableByteBuffer paddingBuffer = RetainableByteBuffer.allocate(paddingLength, false))
            {
                framesBuffer = RetainableByteBuffer.merge(framesBuffer, paddingBuffer);
            }
            if (LOG.isDebugEnabled())
                LOG.debug("generated {} padding bytes for {}", paddingLength, packet);
        }
        else
        {
            framesBuffer.retain();
        }

        try (RetainableByteBuffer _ = framesBuffer)
        {
            int form = 0b11000000;
            int type = packet.packetType().type(packet.quicVersion()) << 4;
            long packetNumber = packet.packetNumber();
            EncodedPacketNumber encodedPacketNumber = packetNumbers.encode(EncryptionLevel.INITIAL, packetNumber);
            int firstByte = form | type | (encodedPacketNumber.length() - 1);
            packetBuffer.put((byte)firstByte);

            packetBuffer.putInt(packet.quicVersion().code());

            byte[] dstConnectionId = packet.destinationConnectionId();
            packetBuffer.put((byte)dstConnectionId.length);
            packetBuffer.put(dstConnectionId);

            byte[] srcConnectionId = packet.sourceConnectionId();
            packetBuffer.put((byte)srcConnectionId.length);
            packetBuffer.put(srcConnectionId);

            byte[] token = packet.token();
            VarLenInt.encode(packetBuffer, token == null ? 0 : token.length);
            if (token != null)
                packetBuffer.put(token);

            // AEAD encryption produces 16 additional bytes.
            long encryptedFramesLength = framesBuffer.remaining() + 16;
            long packetLength = encodedPacketNumber.length() + encryptedFramesLength;

            VarLenInt.encode(packetBuffer, packetLength);

            encodedPacketNumber.putTo(packetBuffer);

            encrypter.encrypt(EncryptionLevel.INITIAL, packetNumber, framesBuffer, packetBuffer);

            if (LOG.isDebugEnabled())
                LOG.debug("encrypted {} {}", packet, packetBuffer);
        }
    }
}
