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

package org.eclipse.jetty.quic.common.packets;

import java.util.List;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.Frame;

public final class HandshakePacket extends LongHeaderPacket implements Packet.WithFrames
{
    private final long packetNumber;
    private final List<Frame> frames;

    public HandshakePacket(QuicVersion quicVersion, byte[] destinationConnectionId, byte[] sourceConnectionId, long packetNumber, List<Frame> frames)
    {
        this(-1, quicVersion, destinationConnectionId, sourceConnectionId, packetNumber, frames);
    }

    public HandshakePacket(long length, QuicVersion quicVersion, byte[] destinationConnectionId, byte[] sourceConnectionId, long packetNumber, List<Frame> frames)
    {
        super(length, PacketType.HANDSHAKE, quicVersion, destinationConnectionId, sourceConnectionId);
        this.packetNumber = packetNumber;
        this.frames = frames;
    }

    @Override
    public long packetNumber()
    {
        return packetNumber;
    }

    @Override
    public List<Frame> frames()
    {
        return frames;
    }

    @Override
    public String toString()
    {
        return "%s[#%d,%s]".formatted(super.toString(), packetNumber(), frames());
    }
}
