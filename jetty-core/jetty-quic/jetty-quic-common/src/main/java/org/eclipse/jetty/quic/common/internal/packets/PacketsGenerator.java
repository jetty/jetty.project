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
import org.eclipse.jetty.quic.common.packets.LongHeaderPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.ShortHeaderPacket;

public class PacketsGenerator
{
    private final LongHeaderPacketsGenerator longHeaderPacketsGenerator;
    private final ShortHeaderPacketsGenerator shortHeaderPacketsGenerator;

    public PacketsGenerator(PacketNumbers packetNumbers, FramesGenerator framesGenerator, Encrypter encrypter)
    {
        longHeaderPacketsGenerator = new LongHeaderPacketsGenerator(packetNumbers, framesGenerator, encrypter);
        shortHeaderPacketsGenerator = new ShortHeaderPacketsGenerator(packetNumbers, encrypter);
    }

    public void generate(RetainableByteBuffer.Mutable packetAccumulator, Packet packet, RetainableByteBuffer.Mutable framesAccumulator) throws Exception
    {
        switch (packet)
        {
            case LongHeaderPacket longHeader -> longHeaderPacketsGenerator.generate(packetAccumulator, longHeader, framesAccumulator);
            case ShortHeaderPacket shortHeader -> shortHeaderPacketsGenerator.generate(packetAccumulator, shortHeader, framesAccumulator);
        }
    }
}
