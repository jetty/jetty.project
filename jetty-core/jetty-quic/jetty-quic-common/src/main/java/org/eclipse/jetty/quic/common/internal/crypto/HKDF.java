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

package org.eclipse.jetty.quic.common.internal.crypto;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;

public class HKDF
{
    /// Returns the [HKDFParameterSpec] correspondent to the HDKF-Expand-Label function
    /// defined in [RFC 8446, 7.1](https://datatracker.ietf.org/doc/html/rfc8446#section-7.1)].
    ///
    /// The returned value can then be used to derive keys as defined by
    /// [RFC 9001, A.1](https://datatracker.ietf.org/doc/html/rfc9001#section-a.1).
    ///
    /// ```java
    /// // Create the initial pseudo random key using the HKDF-Extract function.
    /// KDF kdf = KDF.getInstance("HKDF-SHA256");
    /// HKDFParameterSpec.Extract spec = HKDFParameterSpec.ofExtract()
    ///     .addSalt(salt)
    ///     .addIKM(destinationConnectionId)
    ///     .extractOnly();
    /// SecretKey prk = kdf.deriveKey("InitialPseudoRandomKey", spec);
    ///
    /// // Derive a key using the HDKF-Expand-Label function.
    /// SecretKey derived = kdf.deriveKey("InitialSecretKey", HKDF.expandLabel(prk, "client in", 32));
    /// ```
    public static HKDFParameterSpec expandLabel(SecretKey key, String quicLabel, int length)
    {
        return HKDFParameterSpec.expandOnly(key, HKDF.hkdfLabel(quicLabel, length), length);
    }

    private static byte[] hkdfLabel(String quicLabel, int length)
    {
        // RFC 8446, 7.1.
        byte[] labelBytes = ("tls13 " + quicLabel).getBytes(StandardCharsets.US_ASCII);
        byte[] hkdfLabel = new byte[2 + 1 + labelBytes.length + 1];
        hkdfLabel[0] = (byte)(length >> 8);
        hkdfLabel[1] = (byte)length;
        hkdfLabel[2] = (byte)labelBytes.length;
        System.arraycopy(labelBytes, 0, hkdfLabel, 3, labelBytes.length);
        // No context, so last byte remains 0 (the context length).
        return  hkdfLabel;
    }

    private HKDF()
    {
    }
}
