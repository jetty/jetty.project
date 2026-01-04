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

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.tls.ext.Extension;
import org.eclipse.jetty.tls.ext.KeyShareExtension;
import org.eclipse.jetty.tls.ext.SignatureAlgorithmsExtension;
import org.eclipse.jetty.tls.ext.SupportedGroupsExtension;
import org.eclipse.jetty.tls.ext.SupportedVersionsExtension;

public final class ClientHelloMessage implements Message
{
    public static ClientHelloMessage newClientHello() throws Exception
    {
        SecureRandom secureRandom = new SecureRandom();
        byte[] random = new byte[32];
        secureRandom.nextBytes(random);

        // Known TLS 1.3 supported cipher suites.
        List<CipherSuite> cipherSuites = List.of(CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_AES_256_GCM_SHA384);

        // Add default extensions, only dealing with
        // those that do not need external information.
        // External code will add other extensions such
        // as SNI and ALPN.

        // Known supported named groups.
        List<NamedGroup> groups = List.of(NamedGroup.x25519, NamedGroup.secp256r1);
        SupportedGroupsExtension supportedGroupsExtension = new SupportedGroupsExtension(groups);

        // KeyPairs and KeyShares.
        List<GroupKeyPair> groupKeyPairs = new ArrayList<>();
        for (NamedGroup group : groups)
        {
            GroupKeyPair from = GroupKeyPair.from(group);
            groupKeyPairs.add(from);
        }
        List<KeyShare> keyShares = groupKeyPairs.stream()
            .map(GroupKeyPair::toKeyShare)
            .toList();
        KeyShareExtension keyShareExtension = new KeyShareExtension(keyShares);

        SupportedVersionsExtension supportedVersionsExtension = new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3));

        SignatureAlgorithmsExtension signatureAlgorithmsExtension = new SignatureAlgorithmsExtension(List.of(SignatureAlgorithm.RSA_PKCS1_SHA256, SignatureAlgorithm.ECDSA_SECP256R1_SHA256));

        List<Extension> extensions = List.of(supportedGroupsExtension, keyShareExtension, supportedVersionsExtension, signatureAlgorithmsExtension);

        ClientHelloMessage message = new ClientHelloMessage(random, cipherSuites, extensions);
        message.groupKeyPairs.addAll(groupKeyPairs);

        return message;
    }

    private final List<GroupKeyPair> groupKeyPairs = new ArrayList<>();
    private final byte[] random;
    private final List<CipherSuite> cipherSuites;
    private final List<Extension> extensions;

    public ClientHelloMessage(byte[] random, List<CipherSuite> cipherSuites, List<Extension> extensions)
    {
        this.random = random;
        this.cipherSuites = cipherSuites;
        this.extensions = extensions;
    }

    @Override
    public Type type()
    {
        return Type.CLIENT_HELLO;
    }

    public byte[] random()
    {
        return random;
    }

    /// @return the cipher suites
    public List<CipherSuite> cipherSuites()
    {
        return cipherSuites;
    }

    /// @return the extensions
    public List<Extension> extensions()
    {
        return extensions;
    }

    private record GroupKeyPair(NamedGroup group, KeyPair keyPair)
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
}
