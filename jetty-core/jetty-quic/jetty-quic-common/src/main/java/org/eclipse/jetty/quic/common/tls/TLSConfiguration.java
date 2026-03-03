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

package org.eclipse.jetty.quic.common.tls;

import java.util.List;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.common.QuicConfiguration;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class TLSConfiguration
{
    private final QuicConfiguration quicConfiguration;
    private final SslContextFactory sslContextFactory;
    private QuicVersion quicVersion;
    private List<String> applicationProtocols;

    public TLSConfiguration(QuicConfiguration quicConfiguration, SslContextFactory sslContextFactory)
    {
        this.quicConfiguration = quicConfiguration;
        this.sslContextFactory = sslContextFactory;
    }

    public QuicConfiguration getQuicConfiguration()
    {
        return quicConfiguration;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    public QuicVersion getQuicVersion()
    {
        return quicVersion;
    }

    public void setQuicVersion(QuicVersion quicVersion)
    {
        this.quicVersion = quicVersion;
    }

    public List<String> getApplicationProtocols()
    {
        return applicationProtocols;
    }

    public void setApplicationProtocols(List<String> applicationProtocols)
    {
        this.applicationProtocols = applicationProtocols;
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(getClass()), hashCode());
    }
}
