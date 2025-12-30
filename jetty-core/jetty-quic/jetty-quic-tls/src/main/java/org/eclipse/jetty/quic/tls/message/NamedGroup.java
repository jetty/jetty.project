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

package org.eclipse.jetty.quic.tls.message;

import java.util.HashMap;
import java.util.Map;

/// The named groups specified in RFC 8442, RFC 8446 and RFC 7919.
///
/// The constant name is lower-case and matches the Java security provider names.
public enum NamedGroup
{
    // Elliptic Curve Groups (ECDHE).
    secp256r1(0x0017),
    secp384r1(0x0018),
    secp521r1(0x0019),
    x25519(0x001d),
    x448(0x001e),

    // Finite Field Groups (DHE).
    ffdhe2048(0x0100),
    ffdhe3072(0x0101),
    ffdhe4096(0x0102),
    ffdhe6144(0x0103),
    ffdhe8192(0x0104);

    private final int code;

    NamedGroup(int code)
    {
        this.code = code;
        Codes.CODES.put(code, this);
    }

    public static NamedGroup from(int code)
    {
        return Codes.CODES.get(code);
    }

    public int code()
    {
        return code;
    }

    private static class Codes
    {
        private static final Map<Integer, NamedGroup> CODES = new HashMap<>();
    }
}
