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

import org.eclipse.jetty.quic.api.Version;

public sealed class LongHeaderPacket extends Packet permits InitialPacket
{
    private final PacketType packetType;
    private final Version version;
    private final byte[] sourceConnectionId;
    private final byte[] destinationConnectionId;

    public LongHeaderPacket(PacketType packetType, Version version, byte[] sourceConnectionId, byte[] destinationConnectionId)
    {
        this.packetType = packetType;
        this.version = version;
        this.sourceConnectionId = sourceConnectionId;
        this.destinationConnectionId = destinationConnectionId;
    }

    public PacketType getPacketType()
    {
        return packetType;
    }

    public Version getVersion()
    {
        return version;
    }

    public byte[] getSourceConnectionId()
    {
        return sourceConnectionId;
    }

    public byte[] getDestinationConnectionId()
    {
        return destinationConnectionId;
    }
}
