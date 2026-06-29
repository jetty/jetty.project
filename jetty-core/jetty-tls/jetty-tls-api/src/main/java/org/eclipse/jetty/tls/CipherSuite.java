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

import java.util.HashMap;
import java.util.Map;

/// A TLS cipher suite.
///
/// NOTE: this class is not an enum because it has an open set of values
/// that are part of TLS messages that are used to derive cryptographic keys.
public final class CipherSuite
{
    private static final Map<Integer, CipherSuite> INSTANCES = new HashMap<>();

    public static final int TLS_AES_128_GCM_SHA256_CODE = 0x1301;
    public static final CipherSuite TLS_AES_128_GCM_SHA256 = create(TLS_AES_128_GCM_SHA256_CODE);

    public static final int TLS_AES_256_GCM_SHA384_CODE = 0x1302;
    public static final CipherSuite TLS_AES_256_GCM_SHA384 = create(TLS_AES_256_GCM_SHA384_CODE);

    public static final int TLS_CHACHA20_POLY1305_SHA256_CODE = 0x1303;
    public static final CipherSuite TLS_CHACHA20_POLY1305_SHA256 = create(TLS_CHACHA20_POLY1305_SHA256_CODE);

    private final int code;

    private CipherSuite(int code)
    {
        this.code = code;
    }

    public int code()
    {
        return code;
    }

    public int keyLength()
    {
        return switch (code())
        {
            case TLS_AES_128_GCM_SHA256_CODE -> 16;
            case TLS_AES_256_GCM_SHA384_CODE -> 32;
            case TLS_CHACHA20_POLY1305_SHA256_CODE -> 32;
            default -> throw new UnsupportedOperationException("unknown cipher suite " + this);
        };
    }

    public int hashLength()
    {
        return switch (code())
        {
            case TLS_AES_128_GCM_SHA256_CODE -> 32;
            case TLS_AES_256_GCM_SHA384_CODE -> 48;
            case TLS_CHACHA20_POLY1305_SHA256_CODE -> 32;
            default -> throw new UnsupportedOperationException("unknown cipher suite " + this);
        };
    }

    public int tagLength()
    {
        return switch (code())
        {
            case TLS_AES_128_GCM_SHA256_CODE -> 16;
            case TLS_AES_256_GCM_SHA384_CODE -> 16;
            case TLS_CHACHA20_POLY1305_SHA256_CODE -> 16;
            default -> throw new UnsupportedOperationException("unknown cipher suite " + this);
        };
    }

    public String algorithm()
    {
        return switch (code())
        {
            case TLS_AES_128_GCM_SHA256_CODE -> "AES";
            case TLS_AES_256_GCM_SHA384_CODE -> "AES";
            case TLS_CHACHA20_POLY1305_SHA256_CODE -> "ChaCha20";
            default -> throw new UnsupportedOperationException("unknown cipher suite " + this);
        };
    }

    public String payloadCipherName()
    {
        return switch (code())
        {
            case TLS_AES_128_GCM_SHA256_CODE -> "AES/GCM/NoPadding";
            case TLS_AES_256_GCM_SHA384_CODE -> "AES/GCM/NoPadding";
            case TLS_CHACHA20_POLY1305_SHA256_CODE -> "ChaCha20-Poly1305";
            default -> throw new UnsupportedOperationException("unknown cipher suite " + this);
        };
    }

    public String headerCipherName()
    {
        return switch (code())
        {
            case TLS_AES_128_GCM_SHA256_CODE -> "AES/ECB/NoPadding";
            case TLS_AES_256_GCM_SHA384_CODE -> "AES/ECB/NoPadding";
            case TLS_CHACHA20_POLY1305_SHA256_CODE -> "ChaCha20";
            default -> throw new UnsupportedOperationException("unknown cipher suite " + this);
        };
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj instanceof CipherSuite that)
            return code == that.code;
        return false;
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(code);
    }

    @Override
    public String toString()
    {
        return "%s[0x%x]".formatted(getClass().getSimpleName(), code);
    }

    public static CipherSuite from(int code)
    {
        CipherSuite result = INSTANCES.get(code);
        return result != null ? result : new CipherSuite(code);
    }

    private static CipherSuite create(int code)
    {
        return INSTANCES.computeIfAbsent(code, CipherSuite::new);
    }
}
