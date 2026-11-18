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

import java.util.List;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HandshakePacketParser implements PacketParser
{
    private static final Logger LOG = LoggerFactory.getLogger(HandshakePacketParser.class);

    private final Decrypter decrypter;
    private final PacketNumbers packetNumbers;
    private final FramesParser framesParser;

    public HandshakePacketParser(Decrypter decrypter, PacketNumbers packetNumbers, FramesParser framesParser)
    {
        this.decrypter = decrypter;
        this.packetNumbers = packetNumbers;
        this.framesParser = framesParser;
    }

    @Override
    public Packet parse(RetainableByteBuffer buffer) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("parsing HandshakePacket {}", buffer);

        long remaining = buffer.remaining();
        RetainableByteBuffer packetBuffer = decrypter.decryptLongHeaderPacket(EncryptionLevel.HANDSHAKE, buffer);
        long packetLength = remaining - buffer.remaining();

        if (LOG.isDebugEnabled())
            LOG.debug("decrypted HandshakePacket {}", packetBuffer);

        try (packetBuffer)
        {
            if (packetBuffer == null)
            {
                buffer.consume(packetLength(buffer));
                return Packet.DISCARD;
            }

            // TODO: we can introduce a listener to invoke here:
            //  listener.onPacketBuffer(packetBuffer, Promise<Boolean> promise);
            //  The promise boolean indicates whether to continue processing.
            //  In this way, we can write a buffer-level proxy for plaintext QUIC.

            if (LOG.isDebugEnabled())
                LOG.debug("parsing HandshakePacket header {}", packetBuffer);

            byte firstByte = packetBuffer.get();
            int encodedPacketNumberLength = (firstByte & 0x03) + 1;

            int versionCode = packetBuffer.getInt();
            QuicVersion quicVersion = QuicVersion.from(versionCode);

            int length = packetBuffer.getByteAsInt();
            byte[] dstConnectionId = new byte[length];
            packetBuffer.get(dstConnectionId);

            length = packetBuffer.getByteAsInt();
            byte[] srcConnectionId = new byte[length];
            packetBuffer.get(srcConnectionId);

            length = VarLenInt.decodeInt(packetBuffer);

            byte[] encodedPacketNumber = new byte[encodedPacketNumberLength];
            packetBuffer.get(encodedPacketNumber);
            long packetNumber = packetNumbers.decode(EncryptionLevel.HANDSHAKE, encodedPacketNumber);

            if (LOG.isDebugEnabled())
                LOG.debug("parsed HandshakePacket header, version={} packetNumber={} length={}", quicVersion, packetNumber, length);

            if (LOG.isDebugEnabled())
                LOG.debug("parsing HandshakePacket payload {}", packetBuffer);

            List<Frame> frames = framesParser.consume(packetBuffer);

            HandshakePacket packet = new HandshakePacket(packetLength, quicVersion, dstConnectionId, srcConnectionId, packetNumber, frames);

            if (LOG.isDebugEnabled())
                LOG.debug("parsed {}", packet);

            return packet;
        }
    }

    private long packetLength(RetainableByteBuffer buffer)
    {
        long position = buffer.readPosition();
        // Form byte and QUIC version.
        long offset = 1 + 4;
        // DCID length and value.
        int dcidLength = buffer.getByteAsInt(position + offset);
        offset += 1 + dcidLength;
        // SCID length and value.
        int scidLength = buffer.getByteAsInt(position + offset);
        offset += 1 + scidLength;
        // Packet length.
        buffer.readPosition(position + offset);
        long payloadLength = VarLenInt.decodeLong(buffer);
        long headerLength = buffer.readPosition() - position;
        buffer.readPosition(position);
        return headerLength + payloadLength;
    }
}
