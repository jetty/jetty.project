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

package org.eclipse.jetty.quic.tls.message;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.quic.tls.internal.GroupKeyPair;

public final class ClientHello implements Message
{
    public static ClientHello newClientHello() throws Exception
    {
        ClientHello clientHello = new ClientHello();

        SecureRandom random = new SecureRandom();
        byte[] randomBytes = new byte[32];
        random.nextBytes(randomBytes);
        clientHello.setRandom(randomBytes);

        // Known TLS 1.3 supported cipher suites.
        clientHello.setCipherSuites(List.of(CipherSuite.TLS_AES_128_GCM_SHA256, CipherSuite.TLS_AES_256_GCM_SHA384));

        // Add default extensions, only dealing with
        // those that do not need external information.
        // External code will add other extensions such
        // as SNI and ALPN.

        // Known supported named groups.
        List<NamedGroup> groups = List.of(NamedGroup.x25519, NamedGroup.secp256r1);
        clientHello.addExtension(new SupportedGroupsExtension(groups));

        // KeyPairs and KeyShares.
        for (NamedGroup group : groups)
        {
            GroupKeyPair from = GroupKeyPair.from(group);
            clientHello.groupKeyPairs.add(from);
        }
        List<KeyShare> keyShares = clientHello.groupKeyPairs.stream()
            .map(GroupKeyPair::toKeyShare)
            .toList();
        clientHello.addExtension(new KeyShareExtension(keyShares));

        clientHello.addExtension(new SupportedVersionsExtension(List.of(TLSVersion.TLS_1_3)));

        clientHello.addExtension(new SignatureAlgorithmsExtension(List.of(SignatureAlgorithm.RSA_PKCS1_SHA256, SignatureAlgorithm.ECDSA_SECP256R1_SHA256)));

        return clientHello;
    }

    private final List<GroupKeyPair> groupKeyPairs = new ArrayList<>();
    private byte[] random;
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
}
