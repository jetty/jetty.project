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
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;

public class LongHeaderPacketsParser
{
    private final Map<Type, PacketParser> parsers = new EnumMap<>(Type.class);
    private State state = State.TYPE;
    private Type type;

    public LongHeaderPacketsParser(Decrypter decrypter, PacketNumbers packetNumbers, FramesParser framesParser)
    {
        // TODO: other types.
        parsers.put(Type.INITIAL, new InitialPacketParser(decrypter, packetNumbers, framesParser));
        parsers.put(Type.HANDSHAKE, new HandshakePacketParser(decrypter, packetNumbers, framesParser));
    }

    public Packet parse(RetainableByteBuffer buffer) throws Exception
    {
        while (true)
        {
            int remaining = buffer.remaining();
            if (remaining == 0)
                return null;
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            switch (state)
            {
                case TYPE ->
                {
                    byte form = byteBuffer.get(byteBuffer.position());
                    if ((form & 0b01000000) == 0)
                    {
                        type = Type.VERSION_NEGOTIATION;
                    }
                    else
                    {
                        int typeBits = (form & 0b00110000) >>> 4;
                        type = switch (typeBits)
                        {
                            case 0 -> Type.INITIAL;
                            case 1 -> Type.ZERO_RTT;
                            case 2 -> Type.HANDSHAKE;
                            case 3 -> Type.RETRY;
                            default -> throw new AssertionError();
                        };
                    }
                    state = State.BODY;
                }
                case BODY ->
                {
                    Packet packet = parsers.get(type).parse(buffer);
                    if (packet != null)
                        state = State.TYPE;
                    return packet;
                }
            }
        }
    }

    private enum State
    {
        TYPE,
        BODY
    }

    private enum Type
    {
        VERSION_NEGOTIATION,
        INITIAL,
        ZERO_RTT,
        HANDSHAKE,
        RETRY
    }
}
