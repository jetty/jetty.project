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

    public static CipherSuite from(int code)
    {
        return Codes.CODES.get(code);
    }

    private static class Codes
    {
        private static final Map<Integer, CipherSuite> CODES = new HashMap<>();
    }
}
