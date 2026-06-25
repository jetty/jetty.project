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

import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.PacketBuffers;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.BufferUtil;
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
            LOG.debug("parsing HandshakePacket {}", BufferUtil.toDetailString(buffer.getByteBuffer()));

        PacketBuffers packetBuffers = decrypter.decryptLongHeaderPacket(EncryptionLevel.HANDSHAKE, buffer);

        if (LOG.isDebugEnabled())
            LOG.debug("decrypted HandshakePacket {}", packetBuffers);

        if (packetBuffers == null)
        {
            buffer.skip(packetLength(buffer));
            return Packet.DISCARD;
        }

        // TODO: we can introduce a PacketBuffers.Listener to invoke here:
        //  listener.onPacketBuffers(packetBuffers, Promise<Boolean> promise);
        //  The promise boolean indicates whether to continue processing.
        //  In this way, we can write a buffer-level proxy for plaintext QUIC.

        RetainableByteBuffer header = packetBuffers.header();
        ByteBuffer byteBuffer = header.getByteBuffer();

        if (LOG.isDebugEnabled())
            LOG.debug("parsing HandshakePacket header {}", BufferUtil.toDetailString(byteBuffer));

        byte form = byteBuffer.get();
        int encodedPacketNumberLength = (form & 0x03) + 1;

        int versionCode = byteBuffer.getInt();
        QuicVersion quicVersion = QuicVersion.from(versionCode);

        int length = byteBuffer.get();
        byte[] dstConnectionId = new byte[length];
        byteBuffer.get(dstConnectionId);

        length = byteBuffer.get();
        byte[] srcConnectionId = new byte[length];
        byteBuffer.get(srcConnectionId);

        length = VarLenInt.decodeInt(byteBuffer);

        byte[] encodedPacketNumber = new byte[encodedPacketNumberLength];
        byteBuffer.get(encodedPacketNumber);
        long packetNumber = packetNumbers.decode(EncryptionLevel.HANDSHAKE, encodedPacketNumber);

        assert byteBuffer.remaining() == 0;
        header.release();

        if (LOG.isDebugEnabled())
            LOG.debug("parsed HandshakePacket header, version={} packetNumber={} length={}", quicVersion, packetNumber, length);

        RetainableByteBuffer payload = packetBuffers.payload();
        if (LOG.isDebugEnabled())
            LOG.debug("parsing HandshakePacket payload {}", BufferUtil.toDetailString(payload.getByteBuffer()));

        List<Frame> frames = framesParser.consume(payload);
        payload.release();

        HandshakePacket packet = new HandshakePacket(quicVersion, dstConnectionId, srcConnectionId, packetNumber, frames);

        if (LOG.isDebugEnabled())
            LOG.debug("parsed {}", packet);

        return packet;
    }

    private long packetLength(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        int position = byteBuffer.position();
        // Form byte and QUIC version.
        int offset = 1 + 4;
        // DCID length and value.
        int dcidLength = byteBuffer.get(position + offset) & 0xFF;
        offset += 1 + dcidLength;
        // SCID length and value.
        int scidLength = byteBuffer.get(position + offset) & 0xFF;
        offset += 1 + scidLength;
        // Packet length.
        byteBuffer.position(position + offset);
        long payloadLength = VarLenInt.decodeLong(byteBuffer);
        int headerLength = byteBuffer.position() - position;
        byteBuffer.position(position);
        return headerLength + payloadLength;
    }
}
