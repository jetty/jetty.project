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

package org.eclipse.jetty.quic.client;

import java.util.List;

import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class QuicClientQuicConfiguration extends ClientQuicConfiguration
{
    private List<SignatureAlgorithm> signatureAlgorithms = List.of(SignatureAlgorithm.ECDSA_SECP256R1_SHA256, SignatureAlgorithm.RSA_PSS_RSAE_SHA256);
    private List<NamedGroup> namedGroups = List.of(NamedGroup.x25519/*, NamedGroup.secp256r1, NamedGroup.ffdhe2048*/);
    private List<CipherSuite> cipherSuites = List.of(CipherSuite.TLS_AES_128_GCM_SHA256);

    public List<SignatureAlgorithm> getSignatureAlgorithms()
    {
        return signatureAlgorithms;
    }

    public void setSignatureAlgorithms(List<SignatureAlgorithm> signatureAlgorithms)
    {
        this.signatureAlgorithms = signatureAlgorithms;
    }

    public List<NamedGroup> getNamedGroups()
    {
        return namedGroups;
    }

    public void setNamedGroups(List<NamedGroup> namedGroups)
    {
        this.namedGroups = namedGroups;
    }

    public List<CipherSuite> getCipherSuites()
    {
        return cipherSuites;
    }

    public void setCipherSuites(List<CipherSuite> cipherSuites)
    {
        this.cipherSuites = cipherSuites;
    }

    // TODO: no, this is could be different per-connection.
    public void configure(SslContextFactory.Client sslContextFactory)
    {
        getImplementationConfiguration().put(SslContextFactory.Client.class.getName(), sslContextFactory);
    }

    public void deconfigure(SslContextFactory.Client sslContextFactory)
    {
        getImplementationConfiguration().remove(SslContextFactory.Client.class.getName());
    }
}
