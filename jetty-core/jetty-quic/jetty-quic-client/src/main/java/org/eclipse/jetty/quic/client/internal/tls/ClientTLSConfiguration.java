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

import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.common.tls.TLSConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public final class ClientTLSConfiguration extends TLSConfiguration
{
    private final QuicClientQuicConfiguration quicConfiguration;
    private final SslContextFactory.Client sslContextFactory;
    private byte[] inputKeyMaterial;

    public ClientTLSConfiguration(QuicClientQuicConfiguration quicConfiguration, SslContextFactory.Client sslContextFactory)
    {
        this.quicConfiguration = quicConfiguration;
        this.sslContextFactory = sslContextFactory;
    }

    public QuicClientQuicConfiguration getClientQuicConfiguration()
    {
        return quicConfiguration;
    }

    public SslContextFactory.Client getSslContextFactory()
    {
        return sslContextFactory;
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
