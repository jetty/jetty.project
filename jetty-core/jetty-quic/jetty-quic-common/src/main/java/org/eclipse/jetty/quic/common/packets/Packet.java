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
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;

public abstract sealed class Packet permits LongHeaderPacket, ShortHeaderPacket
{
    public static boolean isLongHeader(byte form)
    {
        // RFC 9000, 17.2: long header packets have msb == 1.
        return (form & 0b10000000) == 0b10000000;
    }

    public abstract byte[] destinationConnectionId();

    @Override
    public String toString()
    {
        return "%s@%x[dcid=%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), StringUtil.toHexString(destinationConnectionId()));
    }

    public interface Listener
    {
        void onIncomingPacket(Session session, Packet packet);

        void onOutgoingPacket(Session session, Packet packet);
    }
}
