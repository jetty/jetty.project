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

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HTTP3Connection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3Connection.class);

    private final ByteBufferPool byteBufferPool;
    private final MessageParser parser;
    private final ParserListener listener;
    private boolean useInputDirectByteBuffers = true;

    public HTTP3Connection(QuicStreamEndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, MessageParser parser, ParserListener listener)
    {
        super(endPoint, executor);
        this.byteBufferPool = byteBufferPool;
        this.parser = parser;
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
    public void onFillable()
    {
        ByteBuffer buffer = byteBufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
        try
        {
            while (true)
            {
                int filled = getEndPoint().fill(buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}", filled, this);

                if (filled > 0)
                {
                    while (buffer.hasRemaining())
                    {
                        Frame frame = parser.parse(buffer);
                        if (frame == null)
                            break;
                        if (frame instanceof HeadersFrame)
                            frame = ((HeadersFrame)frame).withLast(getEndPoint().isInputShutdown());
                        else if (frame instanceof DataFrame)
                            frame = ((DataFrame)frame).withLast(getEndPoint().isInputShutdown());
                        notifyFrame(frame);
                    }
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
            getEndPoint().close(x);
        }
    }

    private void notifyFrame(Frame frame)
    {
        FrameType frameType = frame.getFrameType();
        switch (frameType)
        {
            case HEADERS:
            {
                notifyHeaders((HeadersFrame)frame);
                break;
            }
            case DATA:
            {
                notifyData((DataFrame)frame);
                break;
            }
            default:
            {
                throw new UnsupportedOperationException("unsupported frame type " + frameType);
            }
        }
    }

    private void notifyHeaders(HeadersFrame frame)
    {
        try
        {
            long streamId = ((QuicStreamEndPoint)getEndPoint()).getStreamId();
            listener.onHeaders(streamId, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }

    private void notifyData(DataFrame frame)
    {
        try
        {
            long streamId = ((QuicStreamEndPoint)getEndPoint()).getStreamId();
            listener.onData(streamId, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure notifying listener {}", listener, x);
        }
    }
}
