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

import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http3.internal.ControlStreamConnection;
import org.eclipse.jetty.http3.parser.ControlParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnidirectionalStreamConnection extends AbstractConnection.NonBlocking implements Connection.UpgradeFrom
{
    private static final Logger LOG = LoggerFactory.getLogger(UnidirectionalStreamConnection.class);

    private final ByteBufferPool bufferPool;
    private final QpackEncoder encoder;
    private final QpackDecoder decoder;
    private final ParserListener listener;
    private final VarLenInt parser = new VarLenInt();
    private boolean useInputDirectByteBuffers = true;
    private RetainableByteBuffer buffer;

    public UnidirectionalStreamConnection(StreamEndPoint endPoint, Executor executor, ByteBufferPool bufferPool, QpackEncoder encoder, QpackDecoder decoder, ParserListener listener)
    {
        super(endPoint, executor);
        this.bufferPool = bufferPool;
        this.encoder = encoder;
        this.decoder = decoder;
        this.listener = listener;
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
        copy.put(buffer.getByteBuffer());
        releaseBuffer();
        copy.flip();
        return copy;
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
                int filled = getEndPoint().fill(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}: {}", filled, this, buffer);

                if (filled > 0)
                {
                    boolean parsed = parser.tryDecode(byteBuffer, value ->
                    {
                        if (!detectAndUpgrade(value))
                            releaseBuffer();
                    });
                    if (parsed)
                        break;
                }
                else if (filled == 0)
                {
                    releaseBuffer();
                    fillInterested();
                    break;
                }
                else
                {
                    releaseBuffer();
                    getEndPoint().close();
                    break;
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not process stream {}", getEndPoint(), x);
            releaseBuffer();
            getEndPoint().close(x);
        }
    }

    private void releaseBuffer()
    {
        buffer.release();
        buffer = null;
    }

    private boolean detectAndUpgrade(long type)
    {
        StreamType streamType = StreamType.from(type);
        if (streamType != null)
        {
            return switch (streamType)
            {
                case CONTROL_STREAM ->
                {
                    ControlParser parser = new ControlParser(listener);
                    ControlStreamConnection newConnection = new ControlStreamConnection(getEndPoint(), getExecutor(), bufferPool, parser);
                    newConnection.setInputBufferSize(getInputBufferSize());
                    newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                    if (LOG.isDebugEnabled())
                        LOG.debug("upgrading to {}", newConnection);
                    getEndPoint().upgrade(newConnection);
                    yield true;
                }
                case ENCODER_STREAM ->
                {
                    EncoderStreamConnection newConnection = new EncoderStreamConnection(getEndPoint(), getExecutor(), bufferPool, decoder, listener);
                    newConnection.setInputBufferSize(getInputBufferSize());
                    newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                    if (LOG.isDebugEnabled())
                        LOG.debug("upgrading to {}", newConnection);
                    getEndPoint().upgrade(newConnection);
                    yield true;
                }
                case DECODER_STREAM ->
                {
                    DecoderStreamConnection newConnection = new DecoderStreamConnection(getEndPoint(), getExecutor(), bufferPool, encoder, listener);
                    newConnection.setInputBufferSize(getInputBufferSize());
                    newConnection.setUseInputDirectByteBuffers(isUseInputDirectByteBuffers());
                    if (LOG.isDebugEnabled())
                        LOG.debug("upgrading to {}", newConnection);
                    getEndPoint().upgrade(newConnection);
                    yield true;
                }
                default ->
                {
                    Throwable failure = new IllegalArgumentException("unsupported stream type: " + streamType);
                    getEndPoint().disconnect(HTTP3ErrorCode.STREAM_CREATION_ERROR.code(), failure, true, Promise.Invocable.noop());
                    yield false;
                }
            };
        }
        else
        {
            if (StreamType.isReserved(type))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("reserved stream type {}, closing {}", Long.toHexString(type), this);
                getEndPoint().disconnect(HTTP3ErrorCode.randomReservedCode(), null, true, Promise.Invocable.noop());
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("invalid stream type {}, closing {}", Long.toHexString(type), this);
                getEndPoint().disconnect(HTTP3ErrorCode.STREAM_CREATION_ERROR.code(), null, true, Promise.Invocable.noop());
            }
            return false;
        }
    }
}
