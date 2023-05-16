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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<Runnable> event = new AtomicReference<>();
    private final RetainableByteBufferPool buffers;
    private final MessageParser parser;
    private boolean useInputDirectByteBuffers = true;
    private RetainableByteBuffer buffer;
    private boolean dataDemand;
    private boolean dataStalled;
    private DataFrame dataFrame;
    private boolean dataLast;
    private boolean hasNetworkData;
    private boolean remotelyClosed;

    public HTTP3StreamConnection(QuicStreamEndPoint endPoint, Executor executor, ByteBufferPool byteBufferPool, MessageParser parser)
    {
        super(endPoint, executor);
        this.buffers = byteBufferPool.asRetainableByteBufferPool();
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
    public void onClose(Throwable cause)
    {
        tryReleaseBuffer(true);
        super.onClose(cause);
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
            LOG.debug("processing dataMode={} on {}", parser.isDataMode(), this);
        if (parser.isDataMode())
            processDataFrames();
        else
            processNonDataFrames();
    }

    private void processDataFrames()
    {
        processDataDemand();
        if (!parser.isDataMode())
        {
            if (hasBuffer() && buffer.hasRemaining())
                processNonDataFrames();
            else
                fillInterested();
        }
    }

    private void processNonDataFrames()
    {
        try
        {
            tryAcquireBuffer();

            MessageParser.Result result = parseAndFill(true);
            if (result == MessageParser.Result.NO_FRAME)
            {
                tryReleaseBuffer(false);
                return;
            }

            if (result == MessageParser.Result.BLOCKED_FRAME)
            {
                // Return immediately because another thread may
                // resume the processing as the stream is unblocked.
                tryReleaseBuffer(false);
                return;
            }

            Runnable action = event.getAndSet(null);
            if (action == null)
                throw new IllegalStateException();
            action.run();

            // TODO: we should also exit if the connection was closed due to errors.
            //  This can be done by overriding relevant methods in MessageListener.

            if (remotelyClosed)
            {
                // We have detected the end of the stream, do not try to fill & parse again.
                // However, the last frame may have caused a write that needs to be flushed.
                getEndPoint().getQuicSession().flush();
                tryReleaseBuffer(false);
                return;
            }

            if (!parser.isDataMode())
                throw new IllegalStateException();

            if (hasBuffer() && buffer.hasRemaining())
            {
                processDataFrames();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("setting fill interest on {}", this);
                tryReleaseBuffer(false);
                fillInterested();
            }
        }
        catch (Throwable x)
        {
            tryReleaseBuffer(true);
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

            tryAcquireBuffer();

            switch (parseAndFill(false))
            {
                case FRAME:
                {
                    Runnable action = event.getAndSet(null);
                    if (action == null)
                        throw new IllegalStateException();
                    action.run();

                    if (parser.isDataMode())
                    {
                        DataFrame frame = dataFrame;
                        dataFrame = null;
                        if (LOG.isDebugEnabled())
                            LOG.debug("read data {} on {}", frame, this);
                        buffer.retain();
                        // Store in a local variable so that the lambda captures the right buffer.
                        RetainableByteBuffer current = buffer;
                        // Release the network buffer here (if empty), since the application may
                        // not be reading more bytes, to avoid to keep around a consumed buffer.
                        tryReleaseBuffer(false);
                        return new Stream.Data(frame, () -> completeReadData(current));
                    }
                    else
                    {
                        // Not anymore in data mode, so it's a trailer frame.
                        tryReleaseBuffer(false);
                        return null;
                    }
                }
                case SWITCH_MODE:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("switching to dataMode=false on {}", this);
                    dataLast = true;
                    parser.setDataMode(false);
                    tryReleaseBuffer(false);
                    return null;
                }
                case NO_FRAME:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("read no data on {}", this);
                    tryReleaseBuffer(false);
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
            cancelDemand();
            tryReleaseBuffer(true);
            getEndPoint().close(HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code(), x);
            // Rethrow so the application has a chance to handle it.
            throw x;
        }
    }

    private void completeReadData(RetainableByteBuffer buffer)
    {
        buffer.release();
        if (LOG.isDebugEnabled())
            LOG.debug("released retained {}", buffer);
    }

    public void demand()
    {
        boolean hasData;
        boolean process = false;
        try (AutoLock ignored = lock.lock())
        {
            hasData = hasNetworkData;
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
        try (AutoLock ignored = lock.lock())
        {
            return dataDemand;
        }
    }

    private void cancelDemand()
    {
        try (AutoLock ignored = lock.lock())
        {
            dataDemand = false;
        }
    }

    private boolean isStalled()
    {
        try (AutoLock ignored = lock.lock())
        {
            return dataStalled;
        }
    }

    private void setHasNetworkData(boolean noData)
    {
        try (AutoLock ignored = lock.lock())
        {
            this.hasNetworkData = noData;
        }
    }

    private void processDataDemand()
    {
        while (true)
        {
            boolean process = true;
            try (AutoLock ignored = lock.lock())
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

    private void tryAcquireBuffer()
    {
        if (!hasBuffer())
        {
            buffer = buffers.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
            if (LOG.isDebugEnabled())
                LOG.debug("acquired {}", buffer);
        }
    }

    private void tryReleaseBuffer(boolean force)
    {
        if (hasBuffer())
        {
            if (buffer.hasRemaining() && force)
                buffer.clear();
            if (!buffer.hasRemaining())
            {
                buffer.release();
                if (LOG.isDebugEnabled())
                    LOG.debug("released {}", buffer);
                buffer = null;
            }
        }
    }

    public boolean hasBuffer()
    {
        return buffer != null;
    }

    private MessageParser.Result parseAndFill(boolean setFillInterest)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill setFillInterest={} on {} with buffer {}", setFillInterest, this, buffer);

            setHasNetworkData(true);

            while (true)
            {
                ByteBuffer byteBuffer = buffer.getBuffer();
                MessageParser.Result result = parser.parse(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("parsed {} on {} with buffer {}", result, this, buffer);
                if (result != MessageParser.Result.NO_FRAME)
                    return result;

                if (buffer.isRetained())
                {
                    buffer.release();
                    RetainableByteBuffer newBuffer = buffers.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
                    if (LOG.isDebugEnabled())
                        LOG.debug("reacquired {} for retained {}", newBuffer, buffer);
                    buffer = newBuffer;
                    byteBuffer = buffer.getBuffer();
                }

                int filled = fill(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {} with buffer {}", filled, this, buffer);

                if (filled > 0)
                    continue;

                if (filled == 0)
                {
                    // Workaround for a Quiche glitch, that sometimes reports
                    // an HTTP/3 frame with last=false, but a subsequent read
                    // of zero bytes reports that the stream is finished.
                    if (!remotelyClosed && getEndPoint().isStreamFinished())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("detected end of stream on {}", this);
                        parser.parse(EMPTY_DATA_FRAME.slice());
                        return MessageParser.Result.FRAME;
                    }

                    setHasNetworkData(false);
                    if (setFillInterest)
                        fillInterested();
                }

                return MessageParser.Result.NO_FRAME;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill failure on {}", this, x);
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

    private void processHeaders(HeadersFrame frame, boolean wasBlocked, Runnable delegate)
    {
        MetaData metaData = frame.getMetaData();
        if (metaData.isRequest())
        {
            // Expect DATA frames now.
            parser.setDataMode(true);
            if (LOG.isDebugEnabled())
                LOG.debug("switching to dataMode=true for request {} on {}", metaData, this);
        }
        else if (metaData.isResponse())
        {
            MetaData.Response response = (MetaData.Response)metaData;
            if (HttpStatus.isInformational(response.getStatus()))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("staying in dataMode=false for response {} on {}", metaData, this);
            }
            else
            {
                // Expect DATA frames now.
                parser.setDataMode(true);
                if (LOG.isDebugEnabled())
                    LOG.debug("switching to dataMode=true for response {} on {}", metaData, this);
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

        delegate.run();

        if (wasBlocked)
            onFillable();
    }

    private void processData(DataFrame frame, Runnable delegate)
    {
        if (dataFrame != null)
            throw new IllegalStateException();
        dataFrame = frame;
        if (frame.isLast())
        {
            dataLast = true;
            shutdownInput();
        }
        delegate.run();
    }

    private void shutdownInput()
    {
        remotelyClosed = true;
        // We want to shutdown the input to avoid "spurious" wakeups where
        // zero bytes could be spuriously read from the EndPoint after the
        // stream is remotely closed by receiving a frame with last=true.
        getEndPoint().shutdownInput(HTTP3ErrorCode.NO_ERROR.code());
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s[demand=%b,stalled=%b,dataMode=%b]", super.toConnectionString(), hasDemand(), isStalled(), parser.isDataMode());
    }

    private class MessageListener extends ParserListener.Wrapper
    {
        private MessageListener(ParserListener listener)
        {
            super(listener);
        }

        @Override
        public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received {}#{} wasBlocked={}", frame, streamId, wasBlocked);
            Runnable delegate = () -> super.onHeaders(streamId, frame, wasBlocked);
            Runnable action = () -> processHeaders(frame, wasBlocked, delegate);
            if (wasBlocked)
                action.run();
            else if (!event.compareAndSet(null, action))
                throw new IllegalStateException();
        }

        @Override
        public void onData(long streamId, DataFrame frame)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received {}#{}", frame, streamId);
            Runnable delegate = () -> super.onData(streamId, frame);
            if (!event.compareAndSet(null, () -> processData(frame, delegate)))
                throw new IllegalStateException();
        }
    }
}
