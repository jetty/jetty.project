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

public enum SignatureAlgorithm
{
    // RSASSA-PKCS1-v1_5 algorithms.
    RSA_PKCS1_SHA256(0X0401),
    RSA_PKCS1_SHA384(0X0501),
    RSA_PKCS1_SHA512(0X0601),

    // ECDSA algorithms.
    ECDSA_SECP256R1_SHA256(0X0403),
    ECDSA_SECP384R1_SHA384(0X0503),
    ECDSA_SECP521R1_SHA512(0X0603),

    // RSASSA-PSS algorithms with public key OID RSAEncryption.
    RSA_PSS_RSAE_SHA256(0X0804),
    RSA_PSS_RSAE_SHA384(0X0805),
    RSA_PSS_RSAE_SHA512(0X0806);

    private final int code;

    SignatureAlgorithm(int code)
    {
        this.code = code;
        Codes.CODES.put(code, this);
    }

    public int code()
    {
        return code;
    }

    public static SignatureAlgorithm from(int code)
    {
        return Codes.CODES.get(code);
    }

    private static class Codes
    {
        private static final Map<Integer, SignatureAlgorithm> CODES = new HashMap<>();
    }
}
