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

import javax.crypto.KDF;
import javax.crypto.SecretKey;
import javax.crypto.spec.HKDFParameterSpec;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.internal.packets.QuicCrypto;
import org.eclipse.jetty.quic.common.tls.HKDF;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToIgnoringCase;

public class HKDFTest
{
    @Test
    public void testHKDFExtract() throws Exception
    {
        // From RFC 9001, Appendix A.
        byte[] connectionId = new byte[]{
            (byte)0x83, (byte)0x94, (byte)0xc8, (byte)0xf0, (byte)0x3e, (byte)0x51, (byte)0x57, (byte)0x08
        };
        HKDFParameterSpec.Extract spec = HKDFParameterSpec.ofExtract()
            .addSalt(QuicCrypto.initialSalt(QuicVersion.V1))
            .addIKM(connectionId)
            .extractOnly();
        KDF kdf = KDF.getInstance("HKDF-SHA256");
        byte[] prk = kdf.deriveKey("InitialPseudoRandomKey", spec).getEncoded();
        // From RFC 9001, Appendix A.1.
        String expected = "7db5df06e7a69e432496adedb00851923595221596ae2ae9fb8115c1e9ed0a44";
        assertThat(StringUtil.toHexString(prk), equalToIgnoringCase(expected));
    }

    @Test
    public void testHKDFExpandLabel() throws Exception
    {
        // From RFC 9001, Appendix A.
        byte[] connectionId = new byte[]{
            (byte)0x83, (byte)0x94, (byte)0xc8, (byte)0xf0, (byte)0x3e, (byte)0x51, (byte)0x57, (byte)0x08
        };
        HKDFParameterSpec.Extract spec = HKDFParameterSpec.ofExtract()
            .addSalt(QuicCrypto.initialSalt(QuicVersion.V1))
            .addIKM(connectionId)
            .extractOnly();
        KDF kdf = KDF.getInstance("HKDF-SHA256");
        SecretKey prk = kdf.deriveKey("InitialPseudoRandomKey", spec);
        SecretKey clientInitial = kdf.deriveKey("InitialSecretKey", HKDF.expandLabel(prk, "client in", 32));
        // From RFC 9001, Appendix A.1.
        String expected = "c00cf151ca5be075ed0ebfb5c80323c42d6b7db67881289af4008f1f6c357aea";
        assertThat(StringUtil.toHexString(clientInitial.getEncoded()), equalToIgnoringCase(expected));
    }
}
