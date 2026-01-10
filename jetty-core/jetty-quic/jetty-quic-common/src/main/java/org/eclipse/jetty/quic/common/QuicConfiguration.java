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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.quic.api.QuicVersion;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public abstract class QuicConfiguration extends ContainerLifeCycle
{
    private final Map<Object, Object> implementationConfiguration = new ConcurrentHashMap<>();
    private QuicVersion version = QuicVersion.V1;
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

    public QuicVersion getQuicVersion()
    {
        return version;
    }

    public void setQuicVersion(QuicVersion version)
    {
        this.version = version;
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

    public Map<Object, Object> getImplementationConfiguration()
    {
        return implementationConfiguration;
    }
}
