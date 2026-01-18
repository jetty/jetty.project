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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.frames.FramesParser;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.PacketNumbers;

public class PacketsParser
{
    private final LongHeaderPacketsParser longHeaderPacketsParser;
    private final ShortHeaderPacketsParser shortHeaderPacketsParser;
    private State state = State.FORM;

    public PacketsParser(Decrypter decrypter, PacketNumbers packetNumbers, FramesParser framesParser)
    {
        longHeaderPacketsParser = new LongHeaderPacketsParser(decrypter, packetNumbers, framesParser);
        shortHeaderPacketsParser = new ShortHeaderPacketsParser(decrypter, packetNumbers, framesParser);
    }

    public void setDestinationConnectionId(byte[] dstConnectionId)
    {
        shortHeaderPacketsParser.setDestinationConnectionId(dstConnectionId);
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
                case FORM ->
                {
                    byte form = byteBuffer.get(byteBuffer.position());
                    state = Packet.isLongHeader(form) ? State.LONG : State.SHORT;
                }
                case LONG ->
                {
                    Packet packet = longHeaderPacketsParser.parse(buffer);
                    if (packet != null)
                        state = State.FORM;
                    return packet;
                }
                case SHORT ->
                {
                    Packet packet = shortHeaderPacketsParser.parse(buffer);
                    if (packet != null)
                        state = State.FORM;
                    return packet;
                }
            }
        }
    }

    private enum State
    {
        FORM,
        LONG,
        SHORT
    }
}
