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
import org.eclipse.jetty.quic.common.packets.LongHeaderPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.ShortHeaderPacket;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class PacketsGenerator
{
    private final LongHeaderPacketsGenerator longHeaderPacketsGenerator;
    private final ShortHeaderPacketsGenerator shortHeaderPacketsGenerator;

    public PacketsGenerator(PacketNumbers packetNumbers, Encrypter encrypter)
    {
        longHeaderPacketsGenerator = new LongHeaderPacketsGenerator(packetNumbers, encrypter);
        shortHeaderPacketsGenerator = new ShortHeaderPacketsGenerator(packetNumbers, encrypter);
    }

    public void generate(Packet packet, RetainableByteBuffer framesBuffer, RetainableByteBuffer.Mutable packetBuffer) throws Exception
    {
        switch (packet)
        {
            case LongHeaderPacket longHeader -> longHeaderPacketsGenerator.generate(longHeader, framesBuffer, packetBuffer);
            case ShortHeaderPacket shortHeader -> shortHeaderPacketsGenerator.generate(shortHeader, framesBuffer, packetBuffer);
            default -> throw new IllegalStateException("unsupported packet type " + packet.getClass());
        }
    }
}
