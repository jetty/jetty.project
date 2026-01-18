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
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;
import org.eclipse.jetty.quic.common.packets.ShortHeaderPacket;

public class ShortHeaderPacketsParser
{
    private final OneRTTPacketParser parser;

    public ShortHeaderPacketsParser(Decrypter decrypter, PacketNumbers packetNumbers, FramesParser framesParser)
    {
        parser = new OneRTTPacketParser(decrypter, packetNumbers, framesParser);
    }

    public void setDestinationConnectionId(byte[] dstConnectionId)
    {
        parser.setDestinationConnectionId(dstConnectionId);
    }

    public ShortHeaderPacket parse(RetainableByteBuffer buffer) throws Exception
    {
        return parser.parse(buffer);
    }
}
