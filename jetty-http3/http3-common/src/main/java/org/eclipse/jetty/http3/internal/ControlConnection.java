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

package org.eclipse.jetty.http3.internal;

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.internal.parser.ControlParser;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ControlConnection extends AbstractConnection implements Connection.UpgradeTo
{
    // SPEC: Control Stream Type.
    public static final int STREAM_TYPE = 0x00;
    private static final Logger LOG = LoggerFactory.getLogger(ControlConnection.class);

    private final ByteBufferPool byteBufferPool;
    private final ControlParser parser;
    private boolean useInputDirectByteBuffers = true;
    private ByteBuffer buffer;

    public ControlConnection(EndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, ControlParser parser)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
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
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    public void onUpgradeTo(ByteBuffer upgrade)
    {
        if (BufferUtil.isEmpty(upgrade))
            return;
        int capacity = Math.max(upgrade.remaining(), getInputBufferSize());
        buffer = byteBufferPool.acquire(capacity, isUseInputDirectByteBuffers());
        buffer.put(upgrade);
    }

    @Override
    public void onFillable()
    {
        try
        {
            if (buffer == null)
                buffer = byteBufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
            while (true)
            {
                int filled = getEndPoint().fill(buffer);
                if (filled > 0)
                {
                    parser.parse(buffer);
                }
                else if (filled == 0)
                {
                    byteBufferPool.release(buffer);
                    fillInterested();
                    break;
                }
                else
                {
                    byteBufferPool.release(buffer);
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
            byteBufferPool.release(buffer);
            buffer = null;
            getEndPoint().close(x);
        }
    }
}
