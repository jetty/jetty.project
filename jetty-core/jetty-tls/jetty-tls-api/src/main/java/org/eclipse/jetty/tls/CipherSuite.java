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

public enum CipherSuite
{
    TLS_AES_128_GCM_SHA256(0x1301),
    TLS_AES_256_GCM_SHA384(0x1302),
    TLS_CHACHA20_POLY1305_SHA256(0x1303);

    private final int code;

    CipherSuite(int code)
    {
        this.code = code;
        Codes.CODES.put(code, this);
    }

    public int code()
    {
        return code;
    }

    public int keyLength()
    {
        return switch (this)
        {
            case TLS_AES_128_GCM_SHA256 -> 16;
            case TLS_AES_256_GCM_SHA384 -> 32;
            case TLS_CHACHA20_POLY1305_SHA256 -> 32;
        };
    }

    public int hashLength()
    {
        return switch (this)
        {
            case TLS_AES_128_GCM_SHA256 -> 32;
            case TLS_AES_256_GCM_SHA384 -> 48;
            case TLS_CHACHA20_POLY1305_SHA256 -> 32;
        };
    }

    public int tagLength()
    {
        return switch (this)
        {
            case TLS_AES_128_GCM_SHA256 -> 16;
            case TLS_AES_256_GCM_SHA384 -> 16;
            case TLS_CHACHA20_POLY1305_SHA256 -> 16;
        };
    }

    public String algorithm()
    {
        return switch (this)
        {
            case TLS_AES_128_GCM_SHA256 -> "AES";
            case TLS_AES_256_GCM_SHA384 -> "AES";
            case TLS_CHACHA20_POLY1305_SHA256 -> "ChaCha20";
        };
    }

    public String payloadCipherName()
    {
        return switch (this)
        {
            case TLS_AES_128_GCM_SHA256 -> "AES/GCM/NoPadding";
            case TLS_AES_256_GCM_SHA384 -> "AES/GCM/NoPadding";
            case TLS_CHACHA20_POLY1305_SHA256 -> "ChaCha20-Poly1305";
        };
    }

    public String headerCipherName()
    {
        return switch (this)
        {
            case TLS_AES_128_GCM_SHA256 -> "AES/ECB/NoPadding";
            case TLS_AES_256_GCM_SHA384 -> "AES/ECB/NoPadding";
            case TLS_CHACHA20_POLY1305_SHA256 -> "ChaCha20";
        };
    }

    public static CipherSuite from(int code)
    {
        return Codes.CODES.get(code);
    }

    private static class Codes
    {
        private static final Map<Integer, CipherSuite> CODES = new HashMap<>();
    }
}
