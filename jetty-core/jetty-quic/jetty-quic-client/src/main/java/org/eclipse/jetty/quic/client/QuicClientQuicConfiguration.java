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

import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.tls.CipherSuite;
import org.eclipse.jetty.tls.NamedGroup;
import org.eclipse.jetty.tls.SignatureAlgorithm;
import org.eclipse.jetty.util.BufferUtil;

public class QuicClientQuicConfiguration extends ClientQuicConfiguration
{
    private List<SignatureAlgorithm> signatureAlgorithms = List.of(SignatureAlgorithm.ECDSA_SECP256R1_SHA256, SignatureAlgorithm.RSA_PSS_RSAE_SHA256);
    private List<NamedGroup> namedGroups = List.of(NamedGroup.x25519/*, NamedGroup.secp256r1, NamedGroup.ffdhe2048*/);
    private List<CipherSuite> cipherSuites = List.of(CipherSuite.TLS_AES_128_GCM_SHA256);
    // A value that does not exceed the usual MTU of 1500 and allows for encapsulation (VPN).
    private int udpPayloadSize = 1344;
    // RFC-9000[18.2].
    private long udpPayloadMaxSize = 65527;
    // RFC-9000[18.2].
    private long ackDelayExponent = 3;
    // RFC-9000[18.2].
    private long ackMaxDelay = 25;
    private boolean enableConnectionMigration;
    // RFC-9000[18.2].
    private long connectionIdMaxCount = 2;

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

    public int getUDPPayloadLength()
    {
        return udpPayloadSize;
    }

    public void setUDPPayloadSize(int udpPayloadSize)
    {
        if (udpPayloadSize < 1200)
            throw new IllegalArgumentException("invalid UDPPayloadSize: " + udpPayloadSize);
        this.udpPayloadSize = udpPayloadSize;
    }

    public long getUDPPayloadMaxSize()
    {
        return udpPayloadMaxSize;
    }

    public void setUDPPayloadMaxSize(long udpPayloadMaxSize)
    {
        if (udpPayloadMaxSize < 1200)
            throw new IllegalArgumentException("invalid UDPPayloadMaxSize: " + udpPayloadMaxSize);
        this.udpPayloadMaxSize = udpPayloadMaxSize;
    }

    public long getAcknowledgmentDelayExponent()
    {
        return ackDelayExponent;
    }

    public void setAcknowledgmentDelayExponent(long ackDelayExponent)
    {
        if (ackMaxDelay < 0 || ackDelayExponent > 20)
            throw new IllegalArgumentException("invalid AcknowledgmentDelayExponent: " + ackDelayExponent);
        this.ackDelayExponent = ackDelayExponent;
    }

    public long getAcknowledgmentMaxDelay()
    {
        return ackMaxDelay;
    }

    public void setAcknowledgmentMaxDelay(long ackMaxDelay)
    {
        if (ackMaxDelay < 0 || ackMaxDelay >= (1 << 14))
            throw new IllegalArgumentException("invalid AcknowledgmentMaxDelay: " + ackMaxDelay);
        this.ackMaxDelay = ackMaxDelay;
    }

    public boolean isEnableConnectionMigration()
    {
        return enableConnectionMigration;
    }

    public void setEnableConnectionMigration(boolean enableConnectionMigration)
    {
        this.enableConnectionMigration = enableConnectionMigration;
    }

    public long getConnectionIdMaxCount()
    {
        return connectionIdMaxCount;
    }

    public void setConnectionIdMaxCount(long connectionIdMaxCount)
    {
        if (connectionIdMaxCount < 2)
            throw new IllegalArgumentException("invalid ConnectionIdMaxCount: " + connectionIdMaxCount);
        this.connectionIdMaxCount = connectionIdMaxCount;
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
