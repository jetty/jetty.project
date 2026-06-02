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

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.common.ZeroRTTStore;
import org.eclipse.jetty.quic.common.tls.TLSConfiguration;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public final class ClientTLSConfiguration extends TLSConfiguration
{
    private final ZeroRTTStore zeroRTTStore;
    private String serverName;
    private TransportParameters transportParameters;
    private byte[] inputKeyMaterial;
    private RetainableByteBuffer earlyData;

    public ClientTLSConfiguration(QuicClientQuicConfiguration quicConfiguration, SslContextFactory.Client sslContextFactory, ZeroRTTStore zeroRTTStore)
    {
        super(quicConfiguration, sslContextFactory);
        this.zeroRTTStore = zeroRTTStore;
    }

    public QuicClientQuicConfiguration getClientQuicConfiguration()
    {
        return (QuicClientQuicConfiguration)super.getQuicConfiguration();
    }

    public SslContextFactory.Client getSslContextFactory()
    {
        return (SslContextFactory.Client)super.getSslContextFactory();
    }

    public ZeroRTTStore getZeroRTTStore()
    {
        return zeroRTTStore;
    }

    public String getServerName()
    {
        return serverName;
    }

    public void setServerName(String serverName)
    {
        this.serverName = serverName;
    }

    public RetainableByteBuffer getEarlyData()
    {
        return earlyData;
    }

    public void setEarlyData(RetainableByteBuffer earlyData)
    {
        this.earlyData = earlyData;
    }

    public TransportParameters getTransportParameters()
    {
        return transportParameters;
    }

    public void setTransportParameters(TransportParameters transportParameters)
    {
        this.transportParameters = transportParameters;
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
