//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * <p>A record that captures HTTP/3 configuration parameters.</p>
 */
@ManagedObject
public class HTTP3Configuration
{
    private long streamIdleTimeout = 30000;
    private int inputBufferSize = 2048;
    private int outputBufferSize = 2048;
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;
    private int maxBlockedStreams = 0;
    private int maxRequestHeadersSize = 8192;
    private int maxResponseHeadersSize = 8192;

    @ManagedAttribute("The stream idle timeout in milliseconds")
    public long getStreamIdleTimeout()
    {
        return streamIdleTimeout;
    }

    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    @ManagedAttribute("The size of the network input buffer")
    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
    }

    @ManagedAttribute("The size of the network output buffer")
    public int getOutputBufferSize()
    {
        return outputBufferSize;
    }

    public void setOutputBufferSize(int outputBufferSize)
    {
        this.outputBufferSize = outputBufferSize;
    }

    @ManagedAttribute("Whether to use direct buffers for input")
    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @ManagedAttribute("Whether to use direct buffers for output")
    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @ManagedAttribute("The max number of QPACK blocked streams")
    public int getMaxBlockedStreams()
    {
        return maxBlockedStreams;
    }

    public void setMaxBlockedStreams(int maxBlockedStreams)
    {
        this.maxBlockedStreams = maxBlockedStreams;
    }

    @ManagedAttribute("The max size of the request headers")
    public int getMaxRequestHeadersSize()
    {
        return maxRequestHeadersSize;
    }

    public void setMaxRequestHeadersSize(int maxRequestHeadersSize)
    {
        this.maxRequestHeadersSize = maxRequestHeadersSize;
    }

    @ManagedAttribute("The max size of the response headers")
    public int getMaxResponseHeadersSize()
    {
        return maxResponseHeadersSize;
    }

    public void setMaxResponseHeadersSize(int maxResponseHeadersSize)
    {
        this.maxResponseHeadersSize = maxResponseHeadersSize;
    }
}
