//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.parser.ControlParser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlStreamConnection extends AbstractConnection implements Connection.UpgradeTo
{
    // SPEC: Control Stream Type.
    public static final long STREAM_TYPE = 0x00;
    private static final Logger LOG = LoggerFactory.getLogger(ControlStreamConnection.class);

    private final ByteBufferPool bufferPool;
    private final ControlParser parser;
    private boolean useInputDirectByteBuffers = true;
    private RetainableByteBuffer buffer;

    public ControlStreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool bufferPool, ControlParser parser)
    {
        super(endPoint, executor);
        this.bufferPool = bufferPool;
        this.parser = parser;
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
    public void onUpgradeTo(ByteBuffer upgrade)
    {
        int capacity = Math.max(upgrade.remaining(), getInputBufferSize());
        buffer = bufferPool.acquire(capacity, isUseInputDirectByteBuffers());
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        int position = BufferUtil.flipToFill(byteBuffer);
        byteBuffer.put(upgrade);
        BufferUtil.flipToFlush(byteBuffer, position);
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
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            while (true)
            {
                // Parse first in case of bytes from the upgrade.
                parser.parse(byteBuffer);

                // Then read from the EndPoint.
                int filled = getEndPoint().fill(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}", filled, this);

                if (filled == 0)
                {
                    buffer.release();
                    buffer = null;
                    fillInterested();
                    break;
                }
                else if (filled < 0)
                {
                    buffer.release();
                    buffer = null;
                    getEndPoint().close();
                    break;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not process control stream {}", getEndPoint(), x);
            buffer.release();
            buffer = null;
            getEndPoint().close(x);
        }
    }
}
