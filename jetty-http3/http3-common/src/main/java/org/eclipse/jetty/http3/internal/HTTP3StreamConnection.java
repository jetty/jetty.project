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
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3StreamConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3StreamConnection.class);
    // An empty DATA frame is the sequence of bytes [0x0, 0x0].
    private static final ByteBuffer EMPTY_DATA_FRAME = ByteBuffer.allocate(2);

    private final AutoLock lock = new AutoLock();
    private final Queue<DataFrame> dataFrames = new ArrayDeque<>();
    private final RetainableByteBufferPool buffers;
    private final MessageParser parser;
    private boolean useInputDirectByteBuffers = true;
    private RetainableByteBuffer buffer;
    private boolean dataMode;
    private boolean dataDemand;
    private boolean dataStalled;
    private boolean dataLast;
    private boolean remotelyClosed;

    public HTTP3StreamConnection(QuicStreamEndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, MessageParser parser)
    {
        super(endPoint, executor);
        this.buffers = RetainableByteBufferPool.findOrAdapt(null, byteBufferPool);
        this.parser = parser;
        parser.init(MessageListener::new);
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
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing dataMode={} on {}", dataMode, this);
        if (dataMode)
            processDataFrames();
        else
            processNonDataFrames();
    }

    private void processDataFrames()
    {
        processDataDemand();
        if (!dataMode)
        {
            if (buffer.hasRemaining())
                processNonDataFrames();
            else
                fillInterested();
        }
    }

    private void processNonDataFrames()
    {
        while (true)
        {
            if (parseAndFill() == MessageParser.Result.NO_FRAME)
                break;
            if (dataMode)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("switching to dataMode=true on {}", this);
                if (buffer.hasRemaining())
                    processDataFrames();
                else
                    fillInterested();
                break;
            }
        }
    }

    protected abstract void onDataAvailable(long streamId);

    public Stream.Data readData()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("reading data on {}", this);

        if (hasDemand())
            throw new IllegalStateException("invalid call to readData(): outstanding demand");

        switch (parseAndFill())
        {
            case FRAME:
            {
                DataFrame frame = dataFrames.poll();
                if (LOG.isDebugEnabled())
                    LOG.debug("read data {} on {}", frame, this);
                if (frame == null)
                    return null;

                buffer.retain();

                return new Stream.Data(frame, buffer::release);
            }
            case MODE_SWITCH:
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("switching to dataMode=false on {}", this);
                dataLast = true;
                dataMode = false;
                parser.setDataMode(false);
                return null;
            }
            case NO_FRAME:
            {
                return null;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }

    public void demand()
    {
        boolean process = false;
        try (AutoLock l = lock.lock())
        {
            dataDemand = true;
            if (dataStalled)
            {
                dataStalled = false;
                process = true;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("demand, wasStalled={} on {}", process, this);
        if (process)
            processDataDemand();
    }

    public boolean hasDemand()
    {
        try (AutoLock l = lock.lock())
        {
            return dataDemand;
        }
    }

    private boolean isStalled()
    {
        try (AutoLock l = lock.lock())
        {
            return dataStalled;
        }
    }

    private void processDataDemand()
    {
        while (true)
        {
            boolean process = true;
            try (AutoLock l = lock.lock())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("processing demand={}, last={} fillInterested={} on {}", dataDemand, dataLast, isFillInterested(), this);
                if (dataDemand)
                {
                    // Do not process if the last frame was already
                    // notified, or if there is demand but no data.
                    if (dataLast || isFillInterested())
                        process = false;
                    else
                        dataDemand = false;
                }
                else
                {
                    dataStalled = true;
                    process = false;
                }
            }

            if (!process)
                return;

            onDataAvailable(getEndPoint().getStreamId());
        }
    }

    private MessageParser.Result parseAndFill()
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill on {} with buffer {}", this, buffer);

            if (buffer == null)
                buffer = buffers.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());

            while (true)
            {
                ByteBuffer byteBuffer = buffer.getBuffer();
                MessageParser.Result result = parser.parse(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("parsed {} on {} with buffer {}", result, this, buffer);
                if (result == MessageParser.Result.FRAME || result == MessageParser.Result.MODE_SWITCH)
                    return result;

                if (buffer.isRetained())
                {
                    buffer.release();
                    buffer = buffers.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
                    byteBuffer = buffer.getBuffer();
                }

                int filled = getEndPoint().fill(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {} with buffer {}", filled, this, buffer);

                if (filled > 0)
                    continue;

                if (!remotelyClosed && getEndPoint().isStreamFinished())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("detected end of stream on {}", this);
                    parser.parse(EMPTY_DATA_FRAME.slice());
                    return MessageParser.Result.FRAME;
                }

                if (filled == 0)
                {
                    buffer.release();
                    buffer = null;
                    fillInterested();
                    break;
                }
                else
                {
                    buffer.release();
                    buffer = null;
                    break;
                }
            }
            return MessageParser.Result.NO_FRAME;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("could not process control stream {}", getEndPoint(), x);
            if (buffer != null)
                buffer.release();
            buffer = null;
            getEndPoint().close(x);
            return MessageParser.Result.NO_FRAME;
        }
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s[demand=%b,stalled=%b,dataMode=%b]", super.toConnectionString(), hasDemand(), isStalled(), dataMode);
    }

    private class MessageListener extends ParserListener.Wrapper
    {
        private MessageListener(ParserListener listener)
        {
            super(listener);
        }

        @Override
        public void onHeaders(long streamId, HeadersFrame frame)
        {
            remotelyClosed = frame.isLast();
            MetaData metaData = frame.getMetaData();
            if (metaData.isRequest() || metaData.isResponse())
            {
                // Expect DATA frames now.
                dataMode = true;
                parser.setDataMode(true);
            }
            else
            {
                // Trailer.
                remotelyClosed = true;
                if (!frame.isLast())
                    frame = new HeadersFrame(metaData, true);
            }
            super.onHeaders(streamId, frame);
        }

        @Override
        public void onData(long streamId, DataFrame frame)
        {
            remotelyClosed = frame.isLast();
            dataLast = frame.isLast();
            dataFrames.offer(frame);
            super.onData(streamId, frame);
        }
    }
}
