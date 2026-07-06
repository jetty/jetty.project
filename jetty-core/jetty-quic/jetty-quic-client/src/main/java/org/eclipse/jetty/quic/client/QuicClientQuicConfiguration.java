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

import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.CongestionController;
import org.eclipse.jetty.quic.common.DefaultFlowController;
import org.eclipse.jetty.quic.common.DefaultStreamsController;
import org.eclipse.jetty.quic.common.FlowController;
import org.eclipse.jetty.quic.common.NewRenoCongestionControllerFactory;
import org.eclipse.jetty.quic.common.StreamsController;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.util.BufferUtil;

public class QuicClientQuicConfiguration extends ClientQuicConfiguration
{
    private List<SignatureAlgorithm> signatureAlgorithms = List.of(SignatureAlgorithm.ECDSA_SECP256R1_SHA256, SignatureAlgorithm.RSA_PSS_RSAE_SHA256);
    private List<NamedGroup> namedGroups = List.of(NamedGroup.x25519/*, NamedGroup.secp256r1, NamedGroup.ffdhe2048*/);
    private List<CipherSuite> cipherSuites = List.of(CipherSuite.TLS_AES_128_GCM_SHA256);
    private CongestionController.Factory congestionControllerFactory = new NewRenoCongestionControllerFactory();
    private RateControl.Factory rateControlFactory = new RateControl.Factory() {};
    private FlowController.Factory flowControllerFactory = new DefaultFlowController.Factory();
    private StreamsController.Factory streamsControllerFactory = new DefaultStreamsController.Factory();

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

    public CongestionController.Factory getCongestionControllerFactory()
    {
        return congestionControllerFactory;
    }

    public void setCongestionControllerFactory(CongestionController.Factory congestionControllerFactory)
    {
        this.congestionControllerFactory = congestionControllerFactory;
    }

    public RateControl.Factory getRateControlFactory()
    {
        return rateControlFactory;
    }

    public void setRateControlFactory(RateControl.Factory rateControlFactory)
    {
        this.rateControlFactory = rateControlFactory;
    }

    public FlowController.Factory getFlowControllerFactory()
    {
        return flowControllerFactory;
    }

    public void setFlowControllerFactory(FlowController.Factory flowControllerFactory)
    {
        this.flowControllerFactory = flowControllerFactory;
    }

    public StreamsController.Factory getStreamsControllerFactory()
    {
        return streamsControllerFactory;
    }

    public void setStreamsControllerFactory(StreamsController.Factory streamsControllerFactory)
    {
        this.streamsControllerFactory = streamsControllerFactory;
    }

    @Override
    public void configure(TransportParameters transportParameters)
    {
        super.configure(transportParameters);
        transportParameters.put(TransportParameters.Ids.MAX_UDP_PAYLOAD_SIZE, getUDPPayloadMaxSize());
        transportParameters.put(TransportParameters.Ids.ACK_DELAY_EXPONENT, getAcknowledgmentDelayExponent());
        transportParameters.put(TransportParameters.Ids.MAX_ACK_DELAY, getAcknowledgmentMaxDelay());
        if (!isEnableConnectionMigration())
            transportParameters.put(TransportParameters.Ids.DISABLE_ACTIVE_MIGRATION, BufferUtil.EMPTY_BYTES);
        transportParameters.put(TransportParameters.Ids.ACTIVE_CONNECTION_ID_LIMIT, getConnectionIdMaxCount());
    }
}
