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
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
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
    private long cryptoMaxData = 64 * 1024;
    private int cryptoQueueMaxSize = 256;
    private int streamQueueMaxSize = 2048;
    private long sessionMaxData;
    private long biLocalStreamMaxData;
    private long biRemoteStreamMaxData;
    private long uniStreamMaxData;
    private long bidirectionalMaxStreams;
    private long unidirectionalMaxStreams;
    // A value that does not exceed the usual MTU of 1500 and allows for encapsulation (VPN).
    private int udpPayloadLength = 1344;
    // RFC-9000 #18.2.
    private long udpPayloadMaxLength = 65527;
    // RFC-9000 #18.2.
    private long ackDelayExponent = 3;
    // RFC-9000 #18.2.
    private long ackMaxDelay = 25;
    private boolean enableConnectionMigration;
    // RFC-9000 #18.2.
    private long connectionIdMaxCount = 2;

    /// @return the supported QUIC versions
    public List<QuicVersion> getQuicVersions()
    {
        return versions;
    }

    /// @param versions the supported QUIC versions
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

    public long getCryptoMaxData()
    {
        return cryptoMaxData;
    }

    /// The max data a local peer is willing to receive in `CRYPTO` frames for each [EncryptionLevel].
    public void setCryptoMaxData(long cryptoMaxData)
    {
        this.cryptoMaxData = cryptoMaxData;
    }

    public int getCryptoQueueMaxSize()
    {
        return cryptoQueueMaxSize;
    }

    /// The max number of queued `CRYPTO` frames.
    ///
    /// A `CRYPTO` frame is queued if it cannot be delivered because
    /// its offset is larger than the current crypto data offset.
    ///
    /// This value bounds the memory retained for `CRYPTO` frames to
    /// `cryptoQueueMaxSize * datagramLength * number_of_encryption_levels`.
    public void setCryptoQueueMaxSize(int cryptoQueueMaxSize)
    {
        this.cryptoQueueMaxSize = cryptoQueueMaxSize;
    }

    public int getStreamQueueMaxSize()
    {
        return streamQueueMaxSize;
    }

    /// The max number of queued `STREAM` frames.
    ///
    /// A `STREAM` frame is queued if it cannot be delivered because
    /// its offset is larger than the current stream data offset.
    ///
    /// This value bounds the memory retained for `STREAM` frames to
    /// `streamQueueMaxSize * datagramLength * max_concurrent_streams`.
    public void setStreamQueueMaxSize(int streamQueueMaxSize)
    {
        this.streamQueueMaxSize = streamQueueMaxSize;
    }

    /// The session max data sent by a local peer to indicate the max data it is willing to receive.
    ///
    /// As data is received and consumed, this value is also used to increment the max data,
    /// which is then sent by the local peer via [MaxDataFrame]s.
    ///
    /// @return the initial session max data
    /// @see TransportParameters.Ids#INITIAL_MAX_DATA
    public long getSessionMaxData()
    {
        return sessionMaxData;
    }

    public void setSessionMaxData(long sessionMaxData)
    {
        this.sessionMaxData = sessionMaxData;
    }

    public long getBidirectionalLocalStreamMaxData()
    {
        return biLocalStreamMaxData;
    }

    public void setBidirectionalLocalStreamMaxData(long bidirectionalLocalStreamMaxData)
    {
        this.biLocalStreamMaxData = bidirectionalLocalStreamMaxData;
    }

    public long getBidirectionalRemoteStreamMaxData()
    {
        return biRemoteStreamMaxData;
    }

    public void setBidirectionalRemoteStreamMaxData(long bidirectionalRemoteStreamMaxData)
    {
        this.biRemoteStreamMaxData = bidirectionalRemoteStreamMaxData;
    }

    public long getUnidirectionalStreamMaxData()
    {
        return uniStreamMaxData;
    }

    public void setUnidirectionalStreamMaxData(long unidirectionalStreamMaxData)
    {
        this.uniStreamMaxData = unidirectionalStreamMaxData;
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
        return udpPayloadLength;
    }

    public void setUDPPayloadLength(int udpPayloadLength)
    {
        if (udpPayloadLength < 1200)
            throw new IllegalArgumentException("invalid UDP payload length: " + udpPayloadLength);
        this.udpPayloadLength = udpPayloadLength;
    }

    public long getUDPPayloadMaxLength()
    {
        return udpPayloadMaxLength;
    }

    public void setUDPPayloadMaxLength(long udpPayloadMaxLength)
    {
        if (udpPayloadMaxLength < 1200)
            throw new IllegalArgumentException("invalid UDPPayloadMaxSize: " + udpPayloadMaxLength);
        this.udpPayloadMaxLength = udpPayloadMaxLength;
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
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_LOCAL, getBidirectionalLocalStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL_REMOTE, getBidirectionalRemoteStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAM_DATA_UNIDIRECTIONAL, getUnidirectionalStreamMaxData());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_BIDIRECTIONAL, getBidirectionalMaxStreams());
        transportParameters.put(TransportParameters.Ids.INITIAL_MAX_STREAMS_UNIDIRECTIONAL, getUnidirectionalMaxStreams());
        transportParameters.putGreaseParameter();
    }
}
