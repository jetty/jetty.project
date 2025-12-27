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

public enum PacketType
{
    INITIAL,
    ZERO_RTT,
    HANDSHAKE,
    RETRY;

    public byte type(Version version)
    {
        // RFC-9000:[17.2].
        // RFC-9369:[3.2].
        return switch (this)
        {
            case INITIAL -> switch (version)
            {
                case V1 -> 0x00;
                case V2 -> 0x01;
            };
            case ZERO_RTT -> switch (version)
            {
                case V1 -> 0x01;
                case V2 -> 0x02;
            };
            case HANDSHAKE -> switch (version)
            {
                case V1 -> 0x02;
                case V2 -> 0x03;
            };
            case RETRY -> switch (version)
            {
                case V1 -> 0x03;
                case V2 -> 0x00;
            };
        };
    }
}
