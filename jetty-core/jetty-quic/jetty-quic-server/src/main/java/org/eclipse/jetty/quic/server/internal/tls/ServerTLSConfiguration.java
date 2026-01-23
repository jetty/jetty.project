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

import java.util.List;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.tls.TLSConfiguration;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public final class ServerTLSConfiguration extends TLSConfiguration
{
    private final QuicServerQuicConfiguration quicConfiguration;
    private final SslContextFactory.Server sslContextFactory;
    private final TransportParameters transportParameters;

    public ServerTLSConfiguration(QuicServerQuicConfiguration quicConfiguration, SslContextFactory.Server sslContextFactory)
    {
        this.quicConfiguration = quicConfiguration;
        this.sslContextFactory = sslContextFactory;
        this.transportParameters = initialize();
    }

    private TransportParameters initialize()
    {
        TransportParameters transportParameters = new TransportParameters();
        transportParameters.put(TransportParameters.Ids.MAX_UDP_PAYLOAD_SIZE, quicConfiguration.getUDPPayloadMaxSize());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_DATA, quicConfiguration.getSessionMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL, quicConfiguration.getLocalBidirectionalStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE, quicConfiguration.getRemoteBidirectionalStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL, quicConfiguration.getUnidirectionalStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, quicConfiguration.getBidirectionalMaxStreams());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_UNIDIRECTIONAL, quicConfiguration.getUnidirectionalMaxStreams());
        transportParameters.put(TransportParameters.Ids.ACK_DELAY_EXPONENT, quicConfiguration.getAcknowledgmentDelayExponent());
        transportParameters.put(TransportParameters.Ids.MAX_ACK_DELAY, quicConfiguration.getAcknowledgmentMaxDelay());
        if (!quicConfiguration.isEnableConnectionMigration())
            transportParameters.put(TransportParameters.Ids.DISABLE_ACTIVE_MIGRATION, BufferUtil.EMPTY_BYTES);
        transportParameters.put(TransportParameters.Ids.ACTIVE_CONNECTION_ID_LIMIT, quicConfiguration.getConnectionIdMaxCount());
        transportParameters.putGreaseParameter();
        return transportParameters;
    }

    public SslContextFactory.Server getSslContextFactory()
    {
        return sslContextFactory;
    }

    public TransportParameters getTransportParameters()
    {
        return transportParameters;
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
}
