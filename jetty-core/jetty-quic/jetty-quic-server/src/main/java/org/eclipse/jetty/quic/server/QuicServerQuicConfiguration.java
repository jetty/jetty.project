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

package org.eclipse.jetty.quic.server;

import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.quic.common.CongestionController;
import org.eclipse.jetty.quic.common.NewRenoCongestionControllerFactory;
import org.eclipse.jetty.quic.server.internal.DefaultSessionTicketFactory;
import org.eclipse.jetty.quic.server.internal.DefaultTokenFactory;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.util.BufferUtil;

public class QuicServerQuicConfiguration extends ServerQuicConfiguration
{
    private List<SignatureAlgorithm> signatureAlgorithms = List.of(SignatureAlgorithm.ECDSA_SECP256R1_SHA256, SignatureAlgorithm.RSA_PSS_RSAE_SHA256);
    private List<NamedGroup> namedGroups = List.of(NamedGroup.x25519/*, NamedGroup.secp256r1, NamedGroup.ffdhe2048*/);
    private List<CipherSuite> cipherSuites = List.of(CipherSuite.TLS_AES_128_GCM_SHA256);
    private TokenFactory tokenFactory = new DefaultTokenFactory();
    private SessionTicket.Factory sessionTicketFactory = new DefaultSessionTicketFactory();
    private CongestionController.Factory congestionControllerFactory = new NewRenoCongestionControllerFactory();
    private int destinationConnectionIdLength = 8;
    private int earlyMaxData;

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

    public TokenFactory getTokenFactory()
    {
        return tokenFactory;
    }

    public void setTokenFactory(TokenFactory tokenFactory)
    {
        this.tokenFactory = Objects.requireNonNull(tokenFactory);
    }

    public SessionTicket.Factory getSessionTicketFactory()
    {
        return sessionTicketFactory;
    }

    public void setSessionTicketFactory(SessionTicket.Factory sessionTicketFactory)
    {
        this.sessionTicketFactory = Objects.requireNonNull(sessionTicketFactory);
    }

    public CongestionController.Factory getCongestionControllerFactory()
    {
        return congestionControllerFactory;
    }

    public void setCongestionControllerFactory(CongestionController.Factory congestionControllerFactory)
    {
        this.congestionControllerFactory = congestionControllerFactory;
    }

    public int getDestinationConnectionIdLength()
    {
        return destinationConnectionIdLength;
    }

    public void setDestinationConnectionIdLength(int destinationConnectionIdLength)
    {
        if (destinationConnectionIdLength < 0 || destinationConnectionIdLength > 20)
            throw new IllegalArgumentException("invalid destinationConnectionId length: " + destinationConnectionIdLength);
        this.destinationConnectionIdLength = destinationConnectionIdLength;
    }

    public int getEarlyMaxData()
    {
        return earlyMaxData;
    }

    public void setEarlyMaxData(int earlyMaxData)
    {
        this.earlyMaxData = earlyMaxData;
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
