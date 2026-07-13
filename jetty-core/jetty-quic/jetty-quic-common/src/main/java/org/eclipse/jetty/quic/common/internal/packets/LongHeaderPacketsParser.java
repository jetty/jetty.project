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
import java.util.EnumMap;
import java.util.Map;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.packets.LongHeaderPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;

public class LongHeaderPacketsParser
{
    private final Map<LongHeaderPacket.PacketType, PacketParser> parsers = new EnumMap<>(LongHeaderPacket.PacketType.class);

    public LongHeaderPacketsParser(Decrypter decrypter, PacketNumbers packetNumbers, FramesParser framesParser)
    {
        // TODO: other types.
        parsers.put(LongHeaderPacket.PacketType.INITIAL, new InitialPacketParser(decrypter, packetNumbers, framesParser));
        parsers.put(LongHeaderPacket.PacketType.HANDSHAKE, new HandshakePacketParser(decrypter, packetNumbers, framesParser));
        parsers.put(LongHeaderPacket.PacketType.RETRY, new RetryPacketParser());
    }

    public Packet parse(RetainableByteBuffer buffer) throws Exception
    {
        while (true)
        {
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            int remaining = byteBuffer.remaining();
            if (remaining == 0)
                return null;

            int position = byteBuffer.position();
            int version = byteBuffer.getInt(position + 1);

            if (version == 0)
                return parseVersionNegotiationPacket(buffer);

            QuicVersion quicVersion = QuicVersion.from(version);
            // RFC-9000 #6.3: packets with reserved versions are discarded.
            if (quicVersion.reserved())
                return Packet.DISCARD;

            byte form = byteBuffer.get(position);
            int type = (form & 0b00110000) >>> 4;
            LongHeaderPacket.PacketType packetType = LongHeaderPacket.PacketType.from(type, quicVersion);
            Packet packet = parsers.get(packetType).parse(buffer);
            if (packet != null)
                return packet;
        }
    }

    private LongHeaderPacket parseVersionNegotiationPacket(RetainableByteBuffer buffer)
    {
        // TODO
        return null;
    }
}
