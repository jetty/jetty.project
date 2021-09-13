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
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StreamConnection extends AbstractConnection implements Connection.UpgradeFrom
{
    private static final Logger LOG = LoggerFactory.getLogger(StreamConnection.class);

    private final ByteBufferPool byteBufferPool;
    private final ParserListener listener;
    private final VarLenInt parser = new VarLenInt();
    private boolean useInputDirectByteBuffers = true;
    private ByteBuffer buffer;

    public StreamConnection(EndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, ParserListener listener)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
        this.listener = listener;
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
    public ByteBuffer onUpgradeFrom()
    {
        int remaining = buffer.remaining();
        ByteBuffer copy = buffer.isDirect() ? ByteBuffer.allocateDirect(remaining) : ByteBuffer.allocate(remaining);
        copy.put(buffer);
        byteBufferPool.release(buffer);
        buffer = null;
        copy.flip();
        return copy;
    }

    @Override
    public void onFillable()
    {
        try
        {
            if (buffer == null)
                buffer = byteBufferPool.acquire(2048, isUseInputDirectByteBuffers());

            while (true)
            {
                int filled = getEndPoint().fill(buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}", filled, this);

                if (filled > 0)
                {
                    if (parser.parseInt(buffer, this::detectAndUpgrade))
                        break;
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
                LOG.debug("could not process stream {}", getEndPoint(), x);
            byteBufferPool.release(buffer);
            buffer = null;
            getEndPoint().close(x);
        }
    }

    private void detectAndUpgrade(int streamType)
    {
        switch (streamType)
        {
            case ControlConnection.STREAM_TYPE:
            {
                ControlParser parser = new ControlParser(listener);
                ControlConnection newConnection = new ControlConnection(getEndPoint(), getExecutor(), byteBufferPool, parser);
                newConnection.setInputBufferSize(getInputBufferSize());
                newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                if (LOG.isDebugEnabled())
                    LOG.debug("upgrading to {}", newConnection);
                getEndPoint().upgrade(newConnection);
                break;
            }
            case EncoderConnection.STREAM_TYPE:
            {
                EncoderConnection newConnection = new EncoderConnection(getEndPoint(), getExecutor());
                newConnection.setInputBufferSize(getInputBufferSize());
                newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                if (LOG.isDebugEnabled())
                    LOG.debug("upgrading to {}", newConnection);
                getEndPoint().upgrade(newConnection);
                break;
            }
            case DecoderConnection.STREAM_TYPE:
            {
                DecoderConnection newConnection = new DecoderConnection(getEndPoint(), getExecutor());
                newConnection.setInputBufferSize(getInputBufferSize());
                newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                if (LOG.isDebugEnabled())
                    LOG.debug("upgrading to {}", newConnection);
                getEndPoint().upgrade(newConnection);
                break;
            }
            default:
            {
                throw new IllegalStateException("unexpected stream type " + Integer.toHexString(streamType));
            }
        }
    }
}
