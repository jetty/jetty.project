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
import org.eclipse.jetty.quic.common.frames.FramesGenerator;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.LongHeaderPacket;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.RetryPacket;

public class LongHeaderPacketsGenerator
{
    private final InitialPacketGenerator initialGenerator;
    private final HandshakePacketGenerator handshakeGenerator;
    private final RetryPacketGenerator retryGenerator;

    public LongHeaderPacketsGenerator(PacketNumbers packetNumbers, FramesGenerator framesGenerator, Encrypter encrypter)
    {
        initialGenerator = new InitialPacketGenerator(packetNumbers, framesGenerator, encrypter);
        handshakeGenerator = new HandshakePacketGenerator(packetNumbers, framesGenerator, encrypter);
        retryGenerator = new RetryPacketGenerator(encrypter);
    }

    public void generate(RetainableByteBuffer.Mutable packetAccumulator, LongHeaderPacket longPacket, RetainableByteBuffer.Mutable framesAccumulator) throws Exception
    {
        switch (longPacket)
        {
            case InitialPacket initialPacket -> initialGenerator.generate(packetAccumulator, initialPacket, framesAccumulator);
            case HandshakePacket handshakePacket -> handshakeGenerator.generate(packetAccumulator, handshakePacket, framesAccumulator);
            case RetryPacket retryPacket -> retryGenerator.generate(packetAccumulator, retryPacket, framesAccumulator);
//            case ZeroRTTPacket zeroRTTPacket -> zeroGenerator.generate(packetAccumulator, zeroRTTPacket);
            default -> throw new UnsupportedOperationException();
        }
    }
}
