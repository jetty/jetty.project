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
import org.eclipse.jetty.util.BufferUtil;

// TODO: convert to record.
public final class ClientHelloMessage implements Message
{
    public static ClientHelloMessage newClientHello() throws Exception
    {
        ClientHelloMessage message = new ClientHelloMessage();

        SecureRandom random = new SecureRandom();
        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);
        message.setRandom(randomBytes);

        // Known TLS 1.3 supported cipher suites.
        message.setCipherSuites(List.of(CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_AES_256_GCM_SHA384));

        // Add default extensions, only dealing with
        // those that do not need external information.
        // External code will add other extensions such
        // as SNI and ALPN.

        // Known supported named groups.
        List<NamedGroup> groups = List.of(NamedGroup.x25519, NamedGroup.secp256r1);
        message.addExtension(new SupportedGroupsExtension(groups));

        // KeyPairs and KeyShares.
        for (NamedGroup group : groups)
        {
            GroupKeyPair from = GroupKeyPair.from(group);
            message.groupKeyPairs.add(from);
        }
        List<KeyShare> keyShares = message.groupKeyPairs.stream()
            .map(GroupKeyPair::toKeyShare)
            .toList();
        message.addExtension(new KeyShareExtension(keyShares));

        message.addExtension(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3)));

        message.addExtension(new SignatureAlgorithmsExtension(List.of(SignatureAlgorithm.RSA_PKCS1_SHA256, SignatureAlgorithm.ECDSA_SECP256R1_SHA256)));

        return message;
    }

    private final List<GroupKeyPair> groupKeyPairs = new ArrayList<>();
    private byte[] random;
    private byte[] sessionId = BufferUtil.EMPTY_BYTES;
    private List<CipherSuite> cipherSuites;
    private List<Extension> extensions;

    @Override
    public Type type()
    {
        return Type.CLIENT_HELLO;
    }

    public byte[] getRandom()
    {
        return random;
    }

    public void setRandom(byte[] random)
    {
        this.random = random;
    }

    public byte[] getSessionId()
    {
        return sessionId;
    }

    public void setSessionId(byte[] sessionId)
    {
        this.sessionId = sessionId;
    }

    /// @return the cipher suites
    public List<CipherSuite> getCipherSuites()
    {
        return cipherSuites;
    }

    /// @param cipherSuites the cipher suites, in order of preference.
    public void setCipherSuites(List<CipherSuite> cipherSuites)
    {
        if (cipherSuites.isEmpty())
            throw new IllegalArgumentException("invalid cipher suites");
        this.cipherSuites = cipherSuites;
    }

    /// @param extension the extension to add
    public void addExtension(Extension extension)
    {
        if (extensions == null)
            extensions = new ArrayList<>();
        extensions.add(extension);
    }

    /// @return the extensions
    public List<Extension> getExtensions()
    {
        return extensions;
    }

    /// @param extensions the extensions
    public void setExtensions(List<Extension> extensions)
    {
        this.extensions = extensions;
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
