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
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HandshakePacketGenerator
{
    private static final Logger LOG = LoggerFactory.getLogger(HandshakePacketGenerator.class);

    private final PacketNumbers packetNumbers;
    private final Encrypter encrypter;

    public HandshakePacketGenerator(PacketNumbers packetNumbers, Encrypter encrypter)
    {
        this.packetNumbers = packetNumbers;
        this.encrypter = encrypter;
    }

    public void generate(HandshakePacket packet, RetainableByteBuffer framesBuffer, RetainableByteBuffer.Mutable packetBuffer) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("generating {}", packet);

        int form = 0b11000000;
        int type = packet.packetType().type(packet.quicVersion()) << 4;
        long packetNumber = packet.packetNumber();
        EncodedPacketNumber encodedPacketNumber = packetNumbers.encode(EncryptionLevel.HANDSHAKE, packetNumber);
        int firstByte = form | type | (encodedPacketNumber.length() - 1);
        packetBuffer.put((byte)firstByte);

        packetBuffer.putInt(packet.quicVersion().code());

        byte[] dstConnectionId = packet.destinationConnectionId();
        packetBuffer.put((byte)dstConnectionId.length);
        packetBuffer.put(dstConnectionId);

        byte[] srcConnectionId = packet.sourceConnectionId();
        packetBuffer.put((byte)srcConnectionId.length);
        packetBuffer.put(srcConnectionId);

        // AEAD encryption produces 16 additional bytes.
        long encryptedFramesLength = framesBuffer.remaining() + 16;
        long packetLength = encodedPacketNumber.length() + encryptedFramesLength;

        VarLenInt.encode(packetBuffer, packetLength);

        encodedPacketNumber.putTo(packetBuffer);

        encrypter.encrypt(EncryptionLevel.HANDSHAKE, packetNumber, framesBuffer, packetBuffer);

        if (LOG.isDebugEnabled())
            LOG.debug("encrypted {} {}", packet, packetBuffer);
    }
}
