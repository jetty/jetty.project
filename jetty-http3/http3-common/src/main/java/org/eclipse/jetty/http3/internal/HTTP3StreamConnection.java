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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

import org.eclipse.jetty.http.HttpStatus;
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
    private final RetainableByteBufferPool buffers;
    private final MessageParser parser;
    private boolean useInputDirectByteBuffers = true;
    private RetainableByteBuffer buffer;
    private boolean applicationMode;
    private boolean parserDataMode;
    private boolean dataDemand;
    private boolean dataStalled;
    private DataFrame dataFrame;
    private boolean dataLast;
    private boolean noData;
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

    public void setApplicationMode(boolean mode)
    {
        this.applicationMode = mode;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        fillInterested();
    }

    @Override
    protected boolean onReadTimeout(Throwable timeout)
    {
        // Idle timeouts are handled by HTTP3Stream.
        return false;
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing parserDataMode={} on {}", parserDataMode, this);
        if (parserDataMode)
            processDataFrames();
        else
            processNonDataFrames();
    }

    private void processDataFrames()
    {
        processDataDemand();
        if (!parserDataMode)
        {
            if (buffer.hasRemaining())
                processNonDataFrames();
            else
                fillInterested();
        }
    }

    private void processNonDataFrames()
    {
        try
        {
            while (true)
            {
                if (parseAndFill(true) == MessageParser.Result.NO_FRAME)
                    break;

                // TODO: we should also exit if the connection was closed due to errors.
                //  There is not yet a isClosed() primitive though.
                if (remotelyClosed)
                {
                    // We have detected the end of the stream,
                    // do not loop around to fill & parse again.
                    // However, the last frame may have
                    // caused a write that we need to flush.
                    getEndPoint().getQuicSession().flush();
                    break;
                }

                if (parserDataMode)
                {
                    if (buffer.hasRemaining())
                    {
                        processDataFrames();
                    }
                    else
                    {
                        if (applicationMode)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("skipping fill interest on {}", this);
                        }
                        else
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("setting fill interest on {}", this);
                            fillInterested();
                        }
                    }
                    break;
                }
            }
        }
        catch (Throwable x)
        {
            long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
            getEndPoint().close(error, x);
            // Notify the application that a failure happened.
            parser.getListener().onStreamFailure(getEndPoint().getStreamId(), error, x);
        }
    }

    protected abstract void onDataAvailable(long streamId);

    public Stream.Data readData()
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("reading data on {}", this);

            if (hasDemand())
                throw new IllegalStateException("invalid call to readData(): outstanding demand");

            switch (parseAndFill(false))
            {
                case FRAME:
                {
                    DataFrame frame = dataFrame;
                    dataFrame = null;
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
                        LOG.debug("switching to parserDataMode=false on {}", this);
                    dataLast = true;
                    parserDataMode = false;
                    parser.setDataMode(false);
                    return null;
                }
                case NO_FRAME:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("read no data on {}", this);
                    return null;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        catch (Throwable x)
        {
            getEndPoint().close(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
            // Rethrow so the application has a chance to handle it.
            throw x;
        }
    }

    public void demand()
    {
        boolean hasData;
        boolean process = false;
        try (AutoLock l = lock.lock())
        {
            hasData = !noData;
            dataDemand = true;
            if (dataStalled && hasData)
            {
                dataStalled = false;
                process = true;
            }
        }
        if (LOG.isDebugEnabled())
            LOG.debug("demand, wasStalled={} hasData={} on {}", process, hasData, this);
        if (process)
            processDataFrames();
        else if (!hasData)
            fillInterested();
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

    private void setNoData(boolean noData)
    {
        try (AutoLock l = lock.lock())
        {
            this.noData = noData;
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

    public MessageParser.Result parseAndFill(boolean setFillInterest)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill setFillInterest={} on {} with buffer {}", setFillInterest, this, buffer);

            if (buffer == null)
                buffer = buffers.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());

            setNoData(false);

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

                int filled = fill(byteBuffer);
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
                    setNoData(true);
                    if (setFillInterest)
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
                LOG.debug("parse+fill failure on {}", this, x);
            if (buffer != null)
                buffer.release();
            buffer = null;
            throw x;
        }
    }

    private int fill(ByteBuffer byteBuffer)
    {
        try
        {
            return getEndPoint().fill(byteBuffer);
        }
        catch (IOException x)
        {
            throw new UncheckedIOException(x.getMessage(), x);
        }
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s[demand=%b,stalled=%b,parserDataMode=%b]", super.toConnectionString(), hasDemand(), isStalled(), parserDataMode);
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
            MetaData metaData = frame.getMetaData();
            if (metaData.isRequest())
            {
                // Expect DATA frames now.
                parserDataMode = true;
                parser.setDataMode(true);
                if (LOG.isDebugEnabled())
                    LOG.debug("switching to parserDataMode=true for request {} on {}", metaData, this);
            }
            else if (metaData.isResponse())
            {
                MetaData.Response response = (MetaData.Response)metaData;
                if (response.getStatus() != HttpStatus.CONTINUE_100)
                {
                    // Expect DATA frames now.
                    parserDataMode = true;
                    parser.setDataMode(true);
                    if (LOG.isDebugEnabled())
                        LOG.debug("switching to parserDataMode=true for response {} on {}", metaData, this);
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("staying in parserDataMode=false for response {} on {}", metaData, this);
                }
            }
            else
            {
                // Trailer.
                if (!frame.isLast())
                    frame = new HeadersFrame(metaData, true);
            }
            if (frame.isLast())
                shutdownInput();
            super.onHeaders(streamId, frame);
        }

        @Override
        public void onData(long streamId, DataFrame frame)
        {
            if (dataFrame != null)
                throw new IllegalStateException();
            dataFrame = frame;
            if (frame.isLast())
            {
                dataLast = true;
                shutdownInput();
            }
            super.onData(streamId, frame);
        }

        private void shutdownInput()
        {
            remotelyClosed = true;
            // We want to shutdown the input to avoid "spurious" wakeups where
            // zero bytes could be spuriously read from the EndPoint after the
            // stream is remotely closed by receiving a frame with last=true.
            getEndPoint().shutdownInput(HTTP3ErrorCode.NO_ERROR.code());
        }
    }
}
