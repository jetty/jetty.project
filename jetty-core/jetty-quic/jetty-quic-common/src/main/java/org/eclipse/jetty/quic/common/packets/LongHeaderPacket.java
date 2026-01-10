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

import org.eclipse.jetty.quic.api.QuicVersion;

public sealed class LongHeaderPacket extends Packet permits HandshakePacket, InitialPacket
{
    private final PacketType packetType;
    private final QuicVersion quicVersion;
    private final byte[] destinationConnectionId;
    private final byte[] sourceConnectionId;

    public LongHeaderPacket(PacketType packetType, QuicVersion quicVersion, byte[] destinationConnectionId, byte[] sourceConnectionId)
    {
        this.packetType = packetType;
        this.quicVersion = quicVersion;
        this.destinationConnectionId = destinationConnectionId;
        this.sourceConnectionId = sourceConnectionId;
    }

    public PacketType packetType()
    {
        return packetType;
    }

    public QuicVersion quicVersion()
    {
        return quicVersion;
    }

    @Override
    public byte[] destinationConnectionId()
    {
        return destinationConnectionId;
    }

    public byte[] sourceConnectionId()
    {
        return sourceConnectionId;
    }

    @Override
    public String toString()
    {
        return "%s[%s]".formatted(super.toString(), quicVersion());
    }

    public enum PacketType
    {
        INITIAL,
        ZERO_RTT,
        HANDSHAKE,
        RETRY;

        public byte type(QuicVersion quicVersion)
        {
            // RFC-9000:[17.2].
            // RFC-9369:[3.2].
            return switch (this)
            {
                case INITIAL -> switch (quicVersion)
                {
                    case V1 -> 0x00;
                    case V2 -> 0x01;
                };
                case ZERO_RTT -> switch (quicVersion)
                {
                    case V1 -> 0x01;
                    case V2 -> 0x02;
                };
                case HANDSHAKE -> switch (quicVersion)
                {
                    case V1 -> 0x02;
                    case V2 -> 0x03;
                };
                case RETRY -> switch (quicVersion)
                {
                    case V1 -> 0x03;
                    case V2 -> 0x00;
                };
            };
        }
    }
}
