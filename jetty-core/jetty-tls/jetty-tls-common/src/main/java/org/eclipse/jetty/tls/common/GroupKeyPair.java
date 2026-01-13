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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import javax.crypto.interfaces.DHPublicKey;

import org.eclipse.jetty.tls.KeyShare;
import org.eclipse.jetty.tls.NamedGroup;

public record GroupKeyPair(NamedGroup group, KeyPair keyPair)
{
    public static GroupKeyPair from(NamedGroup group) throws Exception
    {
        return switch (group)
        {
            case x448, x25519 ->
            {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(group.name());
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                yield new GroupKeyPair(group, keyPair);
            }
            case secp256r1, secp384r1, secp521r1 ->
            {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
                keyPairGenerator.initialize(new ECGenParameterSpec(group.name()));
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                yield new GroupKeyPair(group, keyPair);
            }
            case ffdhe2048, ffdhe3072, ffdhe4096, ffdhe6144, ffdhe8192 ->
            {
                KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DH");
                keyPairGenerator.initialize(Integer.parseInt(group.name().substring("ffdhe".length())));
                KeyPair keyPair = keyPairGenerator.generateKeyPair();
                yield new GroupKeyPair(group, keyPair);
            }
        };
    }

    public KeyShare toKeyShare()
    {
        return switch (group)
        {
            case x448, x25519 ->
            {
                // RFC 8446, Section 4.2.8.2.
                XECPublicKey pub = (XECPublicKey)keyPair().getPublic();
                yield new KeyShare(group, pub.getU().toByteArray());
            }
            case secp256r1, secp384r1, secp521r1 ->
            {
                // RFC 8446, Section 4.2.8.2.
                ECPublicKey pub = (ECPublicKey)keyPair().getPublic();
                ECPoint w = pub.getW();
                byte[] x = w.getAffineX().toByteArray();
                byte[] y = w.getAffineY().toByteArray();
                byte[] keyShare = new byte[1 + x.length + y.length];
                keyShare[0] = 0x04; // Uncompressed point.
                System.arraycopy(x, 0, keyShare, 1, x.length);
                System.arraycopy(y, 0, keyShare, x.length, y.length);
                yield new KeyShare(group, keyShare);
            }
            case ffdhe2048, ffdhe3072, ffdhe4096, ffdhe6144, ffdhe8192 ->
            {
                // RFC 8446, Section 4.2.8.1.
                DHPublicKey pub = (DHPublicKey)keyPair().getPublic();
                yield new KeyShare(group, pub.getY().toByteArray());
            }
        };
    }
}
