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
    V1,
    /// QUIC V2, defined by [RFC 9369](https://datatracker.ietf.org/doc/html/rfc9369).
    V2;

    public static QuicVersion from(int code)
    {
        return switch (code)
        {
            case 1 -> V1;
            case 0x6B3343CF -> V2;
            default -> throw new IllegalArgumentException("invalid QUIC version: " + code);
        };
    }

    /// @return the version number in QUIC long headers packets
    public int code()
    {
        return switch (this)
        {
            case V1 -> 1;
            case V2 -> 0x6B3343CF;
        };
    }
}
