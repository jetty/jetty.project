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

package org.eclipse.jetty.quic.api;

/// The QUIC protocol version.
public enum QuicVersion
{
    /// QUIC V1, defined by [RFC 9000](https://datatracker.ietf.org/doc/html/rfc9000).
    V1(1),

    /// QUIC V2, defined by [RFC 9369](https://datatracker.ietf.org/doc/html/rfc9369).
    V2(0x6B3343CF),

    /// Reserved QUIC versions, [RFC-9000 #15](https://datatracker.ietf.org/doc/html/rfc9000#versions).
    ///
    /// All the reserved versions matching the pattern `0x?A?A?A?A` are
    /// collapsed into this constant, whose [code][#code()] is `0x0A0A0A0A`.
    RESERVED(0x0A0A0A0A);

    public static QuicVersion from(int code)
    {
        return switch (code)
        {
            case 1 -> V1;
            case 0x6B3343CF -> V2;
            default ->
            {
                if ((code & RESERVED.code()) == RESERVED.code())
                    yield RESERVED;
                throw new IllegalArgumentException("invalid_quic_version_" + Integer.toHexString(code));
            }
        };
    }

    private final int code;

    QuicVersion(int code)
    {
        this.code = code;
    }

    /// @return the version number in QUIC long headers packets
    public int code()
    {
        return code;
    }

    /// @return whether this is a reserved QUIC version.
    public boolean reserved()
    {
        return this == RESERVED;
    }
}
