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
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.ShortHeaderPacket;

public class ShortHeaderPacketsGenerator
{
    private final OneRTTPacketGenerator generator;

    public ShortHeaderPacketsGenerator(PacketNumbers packetNumbers, Encrypter encrypter)
    {
        this.generator = new OneRTTPacketGenerator(packetNumbers, encrypter);
    }

    public void generate(RetainableByteBuffer.Mutable packetAccumulator, ShortHeaderPacket packet, RetainableByteBuffer.Mutable framesAccumulator) throws Exception
    {
        generator.generate(packetAccumulator, (OneRTTPacket)packet, framesAccumulator);
    }
}
