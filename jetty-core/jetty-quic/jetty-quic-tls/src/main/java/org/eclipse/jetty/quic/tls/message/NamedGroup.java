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

public enum NamedGroup {
    // Elliptic Curve Groups (ECDHE).
    SECP256R1(0x0017),
    SECP384R1(0x0018),
    SECP521R1(0x0019),
    X25519(0x001D),
    X448(0x001E),

    // Finite Field Groups (DHE).
    FFDHE2048(0x0100),
    FFDHE3072(0x0101),
    FFDHE4096(0x0102),
    FFDHE6144(0x0103),
    FFDHE8192(0x0104);

    private final int value;

    NamedGroup(int value) {
        this.value = value;
    }

    public static NamedGroup from(int group) {
        return switch (group) {
            case 0x0017 -> SECP256R1;
            case 0x0018 -> SECP384R1;
            case 0x0019 -> SECP521R1;
            case 0x001D -> X25519;
            case 0x001E -> X448;
            case 0x0100 -> FFDHE2048;
            case 0x0101 -> FFDHE3072;
            case 0x0102 -> FFDHE4096;
            case 0x0103 -> FFDHE6144;
            case 0x0104 -> FFDHE8192;
            default -> null;
        };
    }

    public int value() {
        return value;
    }
}
