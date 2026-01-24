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

package org.eclipse.jetty.quic.common;

import org.eclipse.jetty.quic.common.packets.HandshakePacket;
import org.eclipse.jetty.quic.common.packets.InitialPacket;
import org.eclipse.jetty.quic.common.packets.OneRTTPacket;
import org.eclipse.jetty.quic.common.packets.Packet;
import org.eclipse.jetty.quic.common.packets.ZeroRTTPacket;

public enum EncryptionLevel
{
    INITIAL,
    ZERO_RTT,
    HANDSHAKE,
    ONE_RTT;

    public static EncryptionLevel from(Packet packet)
    {
        return switch (packet)
        {
            case InitialPacket _ -> INITIAL;
            case HandshakePacket _ -> HANDSHAKE;
            case OneRTTPacket _ -> ONE_RTT;
            case ZeroRTTPacket _ -> ZERO_RTT;
            default -> throw new IllegalArgumentException("invalid packet " + packet);
        };
    }
}
