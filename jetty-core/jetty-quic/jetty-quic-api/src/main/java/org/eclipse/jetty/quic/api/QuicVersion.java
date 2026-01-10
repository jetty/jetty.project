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

    /// @return the salt used to derive initial secrets.
    public byte[] initialSalt()
    {
        return switch (this)
        {
            case V1 -> new byte[]
                {
                    (byte)0x38, (byte)0x76, (byte)0x2C, (byte)0xF7, (byte)0xF5, (byte)0x59, (byte)0x34, (byte)0xB3,
                    (byte)0x4D, (byte)0x17, (byte)0x9A, (byte)0xE6, (byte)0xA4, (byte)0xC8, (byte)0x0C, (byte)0xAD,
                    (byte)0xCC, (byte)0xBB, (byte)0x7F, (byte)0x0A
                };
            case V2 -> new byte[]
                {
                    (byte)0x0D, (byte)0xED, (byte)0xE3, (byte)0xDE, (byte)0xF7, (byte)0x00, (byte)0xA6, (byte)0xDB,
                    (byte)0x81, (byte)0x93, (byte)0x81, (byte)0xBE, (byte)0x6E, (byte)0x26, (byte)0x9D, (byte)0xCB,
                    (byte)0xF9, (byte)0xBD, (byte)0x2E, (byte)0xD9
                };
        };
    }

    /// @return the QUIC label used to derive the AEAD key.
    public String encryptionLabel()
    {
        return switch (this)
        {
            case V1 -> "quic key";
            case V2 -> "quicv2 key";
        };
    }

    /// @return the QUIC label used to derive the initialization vector.
    public String initializationVectorLabel()
    {
        return switch (this)
        {
            case V1 -> "quic iv";
            case V2 -> "quicv2 iv";
        };
    }

    /// @return the QUIC label used to derive the header protection.
    public String headerProtectionLabel()
    {
        return switch (this)
        {
            case V1 -> "quic hp";
            case V2 -> "quicv2 hp";
        };
    }
}
