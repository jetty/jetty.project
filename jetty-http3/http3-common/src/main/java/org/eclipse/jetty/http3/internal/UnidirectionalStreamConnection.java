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

import org.eclipse.jetty.http3.internal.parser.ControlParser;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.quic.common.StreamType;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnidirectionalStreamConnection extends AbstractConnection implements Connection.UpgradeFrom
{
    private static final Logger LOG = LoggerFactory.getLogger(UnidirectionalStreamConnection.class);

    private final ByteBufferPool byteBufferPool;
    private final QpackEncoder encoder;
    private final QpackDecoder decoder;
    private final ParserListener listener;
    private final VarLenInt parser = new VarLenInt();
    private boolean useInputDirectByteBuffers = true;
    private ByteBuffer buffer;

    public UnidirectionalStreamConnection(QuicStreamEndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, QpackEncoder encoder, QpackDecoder decoder, ParserListener listener)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
        this.encoder = encoder;
        this.decoder = decoder;
        this.listener = listener;
    }

    @Override
    public QuicStreamEndPoint getEndPoint()
    {
        return (QuicStreamEndPoint)super.getEndPoint();
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
                    LOG.debug("filled {} on {}: {}", filled, this, BufferUtil.toDetailString(buffer));

                if (filled > 0)
                {
                    if (parser.decode(buffer, this::detectAndUpgrade))
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

    private void detectAndUpgrade(long streamType)
    {
        if (streamType == ControlStreamConnection.STREAM_TYPE)
        {
            ControlParser parser = new ControlParser(listener);
            ControlStreamConnection newConnection = new ControlStreamConnection(getEndPoint(), getExecutor(), byteBufferPool, parser);
            newConnection.setInputBufferSize(getInputBufferSize());
            newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
            if (LOG.isDebugEnabled())
                LOG.debug("upgrading to {}", newConnection);
            getEndPoint().upgrade(newConnection);
        }
        else if (streamType == EncoderStreamConnection.STREAM_TYPE)
        {
            EncoderStreamConnection newConnection = new EncoderStreamConnection(getEndPoint(), getExecutor(), byteBufferPool, decoder);
            newConnection.setInputBufferSize(getInputBufferSize());
            newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
            if (LOG.isDebugEnabled())
                LOG.debug("upgrading to {}", newConnection);
            getEndPoint().upgrade(newConnection);
        }
        else if (streamType == DecoderStreamConnection.STREAM_TYPE)
        {
            DecoderStreamConnection newConnection = new DecoderStreamConnection(getEndPoint(), getExecutor(), byteBufferPool, encoder);
            newConnection.setInputBufferSize(getInputBufferSize());
            newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
            if (LOG.isDebugEnabled())
                LOG.debug("upgrading to {}", newConnection);
            getEndPoint().upgrade(newConnection);
        }
        else
        {
            if (StreamType.isReserved(streamType))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("reserved stream type {}, closing {}", Long.toHexString(streamType), this);
                getEndPoint().close(HTTP3ErrorCode.randomReservedCode(), null);
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("unsupported stream type {}, closing {}", Long.toHexString(streamType), this);
                getEndPoint().close(HTTP3ErrorCode.STREAM_CREATION_ERROR.code(), null);
            }
        }
    }
}
