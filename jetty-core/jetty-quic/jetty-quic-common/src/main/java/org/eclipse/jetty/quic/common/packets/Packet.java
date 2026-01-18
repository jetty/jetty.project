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

import org.eclipse.jetty.quic.api.Session;

public sealed interface Packet permits LongHeaderPacket, Packet.WithPacketNumber, ShortHeaderPacket
{
    static boolean isLongHeader(byte form)
    {
        // RFC 9000, 17.2: long header packets have msb == 1.
        return (form & 0b10000000) == 0b10000000;
    }

    byte[] destinationConnectionId();

    sealed interface WithPacketNumber extends Packet permits HandshakePacket, InitialPacket, OneRTTPacket, ZeroRTTPacket
    {
        long packetNumber();
    }

    //  TODO: javadoc this interface, can be used to drop packets in tests
    //   to simulate network failures.
    interface Listener
    {
        default void onIncomingPacket(Session session, Packet packet)
        {
        }

        default void onOutgoingPacket(Session session, Packet packet)
        {
        }

        // TODO: javadoc this because it's the mechanism to
        //  customize packet processing in QuicSession.
        class Wrapper implements Listener
        {
        }
    }
}
