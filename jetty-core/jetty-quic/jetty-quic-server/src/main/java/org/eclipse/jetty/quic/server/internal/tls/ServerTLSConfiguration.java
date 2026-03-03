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

package org.eclipse.jetty.quic.server.internal.tls;

import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.tls.TLSConfiguration;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public final class ServerTLSConfiguration extends TLSConfiguration
{
    private final TransportParameters transportParameters;

    public ServerTLSConfiguration(QuicServerQuicConfiguration quicConfiguration, SslContextFactory.Server sslContextFactory)
    {
        super(quicConfiguration, sslContextFactory);
        this.transportParameters = new TransportParameters();
        quicConfiguration.configure(transportParameters);
    }

    public QuicServerQuicConfiguration getServerQuicConfiguration()
    {
        return (QuicServerQuicConfiguration)super.getQuicConfiguration();
    }

    public SslContextFactory.Server getSslContextFactory()
    {
        return (SslContextFactory.Server)super.getSslContextFactory();
    }

    public TransportParameters getTransportParameters()
    {
        return transportParameters;
    }
}
