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

package org.eclipse.jetty.http3;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * <p>The HTTP/3 configuration parameters.</p>
 */
@ManagedObject
public class HTTP3Configuration
{
    private long streamIdleTimeout = 30000;
    private int inputBufferSize = 2048;
    private int outputBufferSize = 2048;
    private boolean useInputDirectByteBuffers = true;
    private boolean useOutputDirectByteBuffers = true;
    private int maxBlockedStreams = 64;
    private int maxDecoderTableCapacity = 64 * 1024;
    private int maxEncoderTableCapacity = 64 * 1024;
    private int maxRequestHeadersSize = 8 * 1024;
    private int maxResponseHeadersSize = 8 * 1024;

    @ManagedAttribute("The stream idle timeout in milliseconds")
    public long getStreamIdleTimeout()
    {
        return streamIdleTimeout;
    }

    /**
     * <p>Sets the stream idle timeout in milliseconds.</p>
     * <p>Negative values and zero mean that the stream never times out.</p>
     * <p>Default value is {@code 30} seconds.</p>
     *
     * @param streamIdleTimeout the stream idle timeout in milliseconds
     */
    public void setStreamIdleTimeout(long streamIdleTimeout)
    {
        this.streamIdleTimeout = streamIdleTimeout;
    }

    @ManagedAttribute("The size of the network input buffer")
    public int getInputBufferSize()
    {
        return inputBufferSize;
    }

    /**
     * <p>Sets the size of the buffer used for QUIC network reads.</p>
     * <p>Default value is {@code 2048} bytes.</p>
     *
     * @param inputBufferSize the buffer size in bytes
     */
    public void setInputBufferSize(int inputBufferSize)
    {
        this.inputBufferSize = inputBufferSize;
    }

    @ManagedAttribute("The size of the network output buffer")
    public int getOutputBufferSize()
    {
        return outputBufferSize;
    }

    /**
     * <p>Sets the size of the buffer used for QUIC network writes.</p>
     * <p>Default value is {@code 2048} bytes.</p>
     *
     * @param outputBufferSize the buffer size in bytes
     */
    public void setOutputBufferSize(int outputBufferSize)
    {
        this.outputBufferSize = outputBufferSize;
    }

    @ManagedAttribute("Whether to use direct buffers for network reads")
    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    /**
     * <p>Sets whether to use direct buffers for QUIC network reads.</p>
     * <p>Default value is {@code true}.</p>
     *
     * @param useInputDirectByteBuffers whether to use direct buffers for network reads
     */
    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @ManagedAttribute("Whether to use direct buffers for network writes")
    public boolean isUseOutputDirectByteBuffers()
    {
        return useOutputDirectByteBuffers;
    }

    /**
     * <p>Sets whether to use direct buffers for QUIC network writes.</p>
     * <p>Default value is {@code true}.</p>
     *
     * @param useOutputDirectByteBuffers whether to use direct buffers for network writes
     */
    public void setUseOutputDirectByteBuffers(boolean useOutputDirectByteBuffers)
    {
        this.useOutputDirectByteBuffers = useOutputDirectByteBuffers;
    }

    @ManagedAttribute("The local QPACK max decoder dynamic table capacity")
    public int getMaxDecoderTableCapacity()
    {
        return maxDecoderTableCapacity;
    }

    /**
     * <p>Sets the local QPACK decoder max dynamic table capacity.</p>
     * <p>The default value is {@code 65536} bytes.</p>
     * <p>This value is configured on the local QPACK decoder, and then
     * communicated to the remote QPACK encoder via the SETTINGS frame.</p>
     *
     * @param maxTableCapacity the QPACK decoder dynamic table max capacity
     * @see #setMaxEncoderTableCapacity(int)
     */
    public void setMaxDecoderTableCapacity(int maxTableCapacity)
    {
        this.maxDecoderTableCapacity = maxTableCapacity;
    }

    @ManagedAttribute("The local QPACK initial encoder dynamic table capacity")
    public int getMaxEncoderTableCapacity()
    {
        return maxEncoderTableCapacity;
    }

    /**
     * <p>Sets the local QPACK encoder initial dynamic table capacity.</p>
     * <p>The default value is {@code 65536} bytes.</p>
     * <p>This value is configured in the local QPACK encoder, and may be
     * overwritten by a smaller value received via the SETTINGS frame.</p>
     *
     * @param maxTableCapacity the QPACK encoder dynamic table initial capacity
     * @see #setMaxDecoderTableCapacity(int)
     */
    public void setMaxEncoderTableCapacity(int maxTableCapacity)
    {
        this.maxEncoderTableCapacity = maxTableCapacity;
    }

    @ManagedAttribute("The max number of QPACK blocked streams")
    public int getMaxBlockedStreams()
    {
        return maxBlockedStreams;
    }

    /**
     * <p>Sets the local QPACK decoder max number of blocked streams.</p>
     * <p>The default value is {@code 64}.</p>
     * <p>This value is configured in the local QPACK decoder, and then
     * communicated to the remote QPACK encoder via the SETTINGS frame.</p>
     *
     * @param maxBlockedStreams the QPACK decoder max blocked streams
     */
    public void setMaxBlockedStreams(int maxBlockedStreams)
    {
        this.maxBlockedStreams = maxBlockedStreams;
    }

    @ManagedAttribute("The max size of the request headers")
    public int getMaxRequestHeadersSize()
    {
        return maxRequestHeadersSize;
    }

    /**
     * <p>Sets max request headers size.</p>
     * <p>The default value is {@code 8192} bytes.</p>
     * <p>This value is configured in the server-side QPACK decoder, and
     * then communicated to the client-side QPACK encoder via the SETTINGS
     * frame.</p>
     * <p>The client-side QPACK encoder uses this value to cap, if necessary,
     * the value sent by the server-side QPACK decoder.</p>
     *
     * @param maxRequestHeadersSize the max request headers size in bytes
     */
    public void setMaxRequestHeadersSize(int maxRequestHeadersSize)
    {
        this.maxRequestHeadersSize = maxRequestHeadersSize;
    }

    @ManagedAttribute("The max size of the response headers")
    public int getMaxResponseHeadersSize()
    {
        return maxResponseHeadersSize;
    }

    /**
     * <p>Sets max response headers size.</p>
     * <p>The default value is {@code 8192} bytes.</p>
     * <p>This value is configured in the client-side QPACK decoder, and
     * then communicated to the server-side QPACK encoder via the SETTINGS
     * frame.</p>
     * <p>The server-side QPACK encoder uses this value to cap, if necessary,
     * the value sent by the client-side QPACK decoder.</p>
     *
     * @param maxResponseHeadersSize the max response headers size
     */
    public void setMaxResponseHeadersSize(int maxResponseHeadersSize)
    {
        this.maxResponseHeadersSize = maxResponseHeadersSize;
    }
}
