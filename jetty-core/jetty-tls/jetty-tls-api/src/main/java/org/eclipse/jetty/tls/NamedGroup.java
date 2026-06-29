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

package org.eclipse.jetty.tls;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/// The named groups specified in RFC 8442, RFC 8446 and RFC 7919.
///
/// The constant name is lower-case and matches the Java security provider names.
///
/// NOTE: this class is not an enum because it has an open set of values
/// that are part of TLS messages that are used to derive cryptographic keys,
/// and can have _grease_ values of the form `0x?A?A` per RFC-8701.
public final class NamedGroup
{
    private static final Map<Integer, NamedGroup> INSTANCES = new HashMap<>();

    // Elliptic Curve Groups (ECDHE).
    public static final int secp256r1_code = 0x0017;
    public static final NamedGroup secp256r1 = create(secp256r1_code, "secp256r1");
    
    public static final int secp384r1_code = 0x0018;
    public static final NamedGroup secp384r1 = create(secp384r1_code, "secp384r1");
    
    public static final int secp521r1_code = 0x0019;
    public static final NamedGroup secp521r1 = create(secp521r1_code, "secp521r1");
    
    public static final int x25519_code = 0x001D;
    public static final NamedGroup x25519 = create(x25519_code, "x25519");
    
    public static final int x448_code = 0x001e;
    public static final NamedGroup x448 = create(x448_code, "x448");

    // Finite Field Groups (DHE).
    public static final int ffdhe2048_code = 0x0100;
    public static final NamedGroup ffdhe2048 = create(ffdhe2048_code, "ffdhe2048");

    public static final int ffdhe3072_code = 0x0101;
    public static final NamedGroup ffdhe3072 = create(ffdhe3072_code, "ffdhe3072");

    public static final int ffdhe4096_code = 0x0102;
    public static final NamedGroup ffdhe4096 = create(ffdhe4096_code, "ffdhe4096");

    public static final int ffdhe6144_code = 0x0103;
    public static final NamedGroup ffdhe6144 = create(ffdhe6144_code, "ffdhe6144");

    public static final int ffdhe8192_code = 0x0104;
    public static final NamedGroup ffdhe8192 = create(ffdhe8192_code, "ffdhe8192");

    private final int code;
    private final String name;

    private NamedGroup(int code, String name)
    {
        this.code = code;
        this.name = name;
    }

    public int code()
    {
        return code;
    }

    public String name()
    {
        return name;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj instanceof NamedGroup that)
            return code == that.code;
        return false;
    }

    @Override
    public int hashCode()
    {
        return Integer.hashCode(code);
    }

    @Override
    public String toString()
    {
        return "%s[%s]".formatted(getClass().getSimpleName(), name());
    }

    public static NamedGroup from(int code)
    {
        NamedGroup result = INSTANCES.get(code);
        return result != null ? result : new NamedGroup(code, "0x%x".formatted(code));
    }

    public static Collection<NamedGroup> values()
    {
        return Collections.unmodifiableCollection(INSTANCES.values());
    }

    private static NamedGroup create(int code, String name)
    {
        return INSTANCES.computeIfAbsent(code, c -> new NamedGroup(c, name));
    }
}
