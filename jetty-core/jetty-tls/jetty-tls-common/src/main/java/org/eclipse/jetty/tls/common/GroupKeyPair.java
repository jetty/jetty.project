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
import java.security.spec.ECGenParameterSpec;

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
        return new KeyShare(group, keyPair.getPublic().getEncoded());
    }
}
