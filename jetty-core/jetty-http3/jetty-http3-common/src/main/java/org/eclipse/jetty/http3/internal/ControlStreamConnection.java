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

package org.eclipse.jetty.http3.internal;

import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.parser.ControlParser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlStreamConnection extends AbstractConnection.NonBlocking implements Connection.UpgradeTo
{
    private static final Logger LOG = LoggerFactory.getLogger(ControlStreamConnection.class);

    private final WritableBufferPool bufferPool;
    private final ControlParser parser;
    private boolean useInputDirectByteBuffers = true;
    private RetainableByteBuffer.Mutable buffer;

    public ControlStreamConnection(StreamEndPoint endPoint, Executor executor, WritableBufferPool bufferPool, ControlParser parser)
    {
        super(endPoint, executor);
        this.bufferPool = bufferPool;
        this.parser = parser;
    }

    @Override
    public StreamEndPoint getEndPoint()
    {
        return (StreamEndPoint)super.getEndPoint();
    }

    public boolean isUseInputDirectByteBuffers()
    {
        return useInputDirectByteBuffers;
    }

    public void setUseInputDirectByteBuffers(boolean useInputDirectByteBuffers)
    {
        this.useInputDirectByteBuffers = useInputDirectByteBuffers;
    }

    @Override
    public void onUpgradeTo(RetainableByteBuffer.Mutable upgrade)
    {
        upgrade.retain();
        buffer = upgrade;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        if (buffer != null && buffer.hasRemaining())
            onFillable();
        else
            fillInterested();
    }

    @Override
    public void onFillable()
    {
        try
        {
            if (buffer == null)
                buffer = bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
            while (true)
            {
                // Parse first in case of bytes from the upgrade.
                parser.parse(buffer);

                // Then read from the EndPoint.
                int filled = getEndPoint().fill(buffer.clear());
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}", filled, this);

                if (filled == 0)
                {
                    buffer = Retainable.dispose(buffer);
                    fillInterested();
                    break;
                }
                else if (filled < 0)
                {
                    buffer = Retainable.dispose(buffer);
                    getEndPoint().disconnect(HTTP3ErrorCode.CLOSED_CRITICAL_STREAM_ERROR.code(), null, true, Promise.Invocable.noop());
                    break;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not process control stream {}", getEndPoint(), x);
            buffer = Retainable.dispose(buffer);
            getEndPoint().disconnect(HTTP3ErrorCode.CLOSED_CRITICAL_STREAM_ERROR.code(), x, true, Promise.Invocable.noop());
        }
    }
}
