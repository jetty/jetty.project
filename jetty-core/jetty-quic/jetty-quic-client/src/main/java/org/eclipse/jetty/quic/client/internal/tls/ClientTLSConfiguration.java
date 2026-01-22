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

package org.eclipse.jetty.quic.client.internal.tls;

import java.util.List;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.common.tls.TLSConfiguration;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class ClientTLSConfiguration extends TLSConfiguration
{
    private final QuicClientQuicConfiguration quicConfiguration;
    private final SslContextFactory.Client sslContextFactory;
    private byte[] inputKeyMaterial;

    public ClientTLSConfiguration(QuicClientQuicConfiguration quicConfiguration, SslContextFactory.Client sslContextFactory)
    {
        this.quicConfiguration = quicConfiguration;
        this.sslContextFactory = sslContextFactory;
    }

    public SslContextFactory.Client getSslContextFactory()
    {
        return sslContextFactory;
    }

    public QuicVersion getQuicVersion()
    {
        return quicConfiguration.getQuicVersion();
    }

    public List<SignatureAlgorithm> getSignatureAlgorithms()
    {
        return quicConfiguration.getSignatureAlgorithms();
    }

    public List<NamedGroup> getNamedGroups()
    {
        return quicConfiguration.getNamedGroups();
    }

    public List<CipherSuite> getCipherSuites()
    {
        return quicConfiguration.getCipherSuites();
    }

    public byte[] getInputKeyMaterial()
    {
        return inputKeyMaterial;
    }

    public void setInputKeyMaterial(byte[] inputKeyMaterial)
    {
        this.inputKeyMaterial = inputKeyMaterial;
    }
}
