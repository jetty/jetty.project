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

package org.eclipse.jetty.tls.common;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;

import org.eclipse.jetty.util.BufferUtil;

public class HKDF
{
    /// Returns the [HKDFParameterSpec] correspondent to the HDKF-Expand-Label function
    /// defined in [RFC 8446, 7.1](https://datatracker.ietf.org/doc/html/rfc8446#section-7.1)].
    ///
    /// This is a convenience overload for the cases that have no context bytes.
    /// @see #expandLabel(SecretKey, String, byte[], int)
    public static HKDFParameterSpec expandLabel(SecretKey key, String label, int length)
    {
        return expandLabel(key, label, BufferUtil.EMPTY_BYTES, length);
    }

    /// Returns the [HKDFParameterSpec] correspondent to the HDKF-Expand-Label function
    /// defined in [RFC 8446, 7.1](https://datatracker.ietf.org/doc/html/rfc8446#section-7.1)].
    ///
    /// The returned value can then be used to derive keys:
    ///
    /// ```java
    /// // Create the initial pseudo random key using the HKDF-Extract function.
    /// KDF kdf = KDF.getInstance("HKDF-SHA256");
    /// HKDFParameterSpec.Extract spec = HKDFParameterSpec.ofExtract()
    ///     .addSalt(salt)
    ///     .addIKM(inputKeyMaterial)
    ///     .extractOnly();
    /// SecretKey prk = kdf.deriveKey("PseudoRandomKey", spec);
    ///
    /// // Derive a key using the HDKF-Expand-Label function.
    /// SecretKey secretKey = kdf.deriveKey("SecretKey", HKDF.expandLabel(prk, "label", new byte[0], 32));
    /// ```
    public static HKDFParameterSpec expandLabel(SecretKey key, String label, byte[] context, int length)
    {
        return HKDFParameterSpec.expandOnly(key, HKDF.hkdfLabel(label, context, length), length);
    }

    private static byte[] hkdfLabel(String quicLabel, byte[] context, int length)
    {
        // RFC 8446, 7.1.
        byte[] labelBytes = ("tls13 " + quicLabel).getBytes(StandardCharsets.US_ASCII);
        byte[] hkdfLabel = new byte[2 + 1 + labelBytes.length + 1 + context.length];
        hkdfLabel[0] = (byte)(length >> 8);
        hkdfLabel[1] = (byte)length;
        hkdfLabel[2] = (byte)labelBytes.length;
        int offset = 3;
        System.arraycopy(labelBytes, 0, hkdfLabel, offset, labelBytes.length);
        offset += labelBytes.length;
        hkdfLabel[offset] = (byte)context.length;
        offset += 1;
        System.arraycopy(context, 0, hkdfLabel, offset, context.length);
        return  hkdfLabel;
    }

    private HKDF()
    {
    }
}
