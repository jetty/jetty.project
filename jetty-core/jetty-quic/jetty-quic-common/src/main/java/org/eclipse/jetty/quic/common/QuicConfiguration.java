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

package org.eclipse.jetty.quic.common;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.quic.api.frames.TransportParameters;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public abstract class QuicConfiguration extends ContainerLifeCycle
{
    private final Map<Object, Object> implementationConfiguration = new ConcurrentHashMap<>();
    private List<QuicVersion> versions = List.of(QuicVersion.V1);
    private int inputBufferSize = 2048;
    private boolean useInputDirectByteBuffers = true;
    private int outputBufferSize = 2048;
    private boolean useOutputDirectByteBuffers = true;
    private int minInputBufferSpace = 1500;
    private long streamIdleTimeout;
    private long sessionMaxData;
    private long localBidirectionalStreamMaxData;
    private long remoteBidirectionalStreamMaxData;
    private long unidirectionalStreamMaxData;
    private long bidirectionalMaxStreams;
    private long unidirectionalMaxStreams;
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

    public List<QuicVersion> getQuicVersions()
    {
        return versions;
    }

    public void setQuicVersions(List<QuicVersion> versions)
    {
        if (versions.isEmpty())
            throw new IllegalArgumentException("invalid QUIC versions list");
        this.versions = versions;
    }

    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    public int getOutputBufferSize()
    {
        return outputBufferSize;
    }

    public void setOutputBufferSize(int outputBufferSize)
    {
        this.outputBufferSize = outputBufferSize;
    }

    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    public int getMinInputBufferSpace()
    {
        return minInputBufferSpace;
    }

    public void setMinInputBufferSpace(int minInputBufferSpace)
    {
        this.minInputBufferSpace = minInputBufferSpace;
    }

    public long getStreamIdleTimeout()
    {
        return streamIdleTimeout;
    }

    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    public long getSessionMaxData()
    {
        return sessionMaxData;
    }

    public void setSessionMaxData(long sessionMaxData)
    {
        this.sessionMaxData = sessionMaxData;
    }

    public long getLocalBidirectionalStreamMaxData()
    {
        return localBidirectionalStreamMaxData;
    }

    public void setLocalBidirectionalStreamMaxData(long localBidirectionalStreamMaxData)
    {
        this.localBidirectionalStreamMaxData = localBidirectionalStreamMaxData;
    }

    public long getRemoteBidirectionalStreamMaxData()
    {
        return remoteBidirectionalStreamMaxData;
    }

    public void setRemoteBidirectionalStreamMaxData(long remoteBidirectionalStreamMaxData)
    {
        this.remoteBidirectionalStreamMaxData = remoteBidirectionalStreamMaxData;
    }

    public long getUnidirectionalStreamMaxData()
    {
        return unidirectionalStreamMaxData;
    }

    public void setUnidirectionalStreamMaxData(long unidirectionalStreamMaxData)
    {
        this.unidirectionalStreamMaxData = unidirectionalStreamMaxData;
    }

    public long getBidirectionalMaxStreams()
    {
        return bidirectionalMaxStreams;
    }

    public void setBidirectionalMaxStreams(long bidirectionalMaxStreams)
    {
        this.bidirectionalMaxStreams = bidirectionalMaxStreams;
    }

    public long getUnidirectionalMaxStreams()
    {
        return unidirectionalMaxStreams;
    }

    public void setUnidirectionalMaxStreams(long unidirectionalMaxStreams)
    {
        this.unidirectionalMaxStreams = unidirectionalMaxStreams;
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

    public Map<Object, Object> getImplementationConfiguration()
    {
        return implementationConfiguration;
    }

    public void configure(TransportParameters transportParameters)
    {
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_DATA, getSessionMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL, getLocalBidirectionalStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE, getRemoteBidirectionalStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL, getUnidirectionalStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, getBidirectionalMaxStreams());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_UNIDIRECTIONAL, getUnidirectionalMaxStreams());
        transportParameters.putGreaseParameter();
    }
}
