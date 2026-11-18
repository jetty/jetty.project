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

import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.LongHeaderPacket;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.RetryPacket;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class LongHeaderPacketsGenerator
{
    private final InitialPacketGenerator initialGenerator;
    private final HandshakePacketGenerator handshakeGenerator;
    private final RetryPacketGenerator retryGenerator;

    public LongHeaderPacketsGenerator(PacketNumbers packetNumbers, Encrypter encrypter)
    {
        initialGenerator = new InitialPacketGenerator(packetNumbers, encrypter);
        handshakeGenerator = new HandshakePacketGenerator(packetNumbers, encrypter);
        retryGenerator = new RetryPacketGenerator();
    }

    public void generate(LongHeaderPacket packet, RetainableByteBuffer framesBuffer, RetainableByteBuffer.Mutable packetBuffer) throws Exception
    {
        switch (packet)
        {
            case InitialPacket initialPacket -> initialGenerator.generate(initialPacket, framesBuffer, packetBuffer);
            case HandshakePacket handshakePacket -> handshakeGenerator.generate(handshakePacket, framesBuffer, packetBuffer);
            case RetryPacket retryPacket -> retryGenerator.generate(retryPacket, packetBuffer);
//            case ZeroRTTPacket zeroRTTPacket -> zeroGenerator.generate(zerRTTPacket, framesBuffer, packetBuffer);
            default -> throw new UnsupportedOperationException();
        }
    }
}
