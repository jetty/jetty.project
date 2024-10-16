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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.QuicStreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3StreamConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3StreamConnection.class);
    // An empty DATA frame is the sequence of bytes [0x0, 0x0].
    private static final ByteBuffer EMPTY_DATA_FRAME = ByteBuffer.allocate(2);

    private final Callback fillableCallback = new FillableCallback();
    private final AtomicReference<Runnable> action = new AtomicReference<>();
    private final ByteBufferPool bufferPool;
    private final MessageParser parser;
    private boolean useInputDirectByteBuffers = true;
    private HTTP3Stream stream;
    private RetainableByteBuffer inputBuffer;
    private boolean remotelyClosed;

    public HTTP3StreamConnection(QuicStreamEndPoint endPoint, Executor executor, ByteBufferPool bufferPool, MessageParser parser)
    {
        super(endPoint, executor);
        this.bufferPool = bufferPool;
        this.parser = parser;
        parser.init(MessageListener::new);
    }

    public void onFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFailure on {}", this, failure);
        tryReleaseInputBuffer(true);
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

    void setStream(HTTP3Stream stream)
    {
        this.stream = stream;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        setFillInterest();
    }

    @Override
    protected boolean onReadTimeout(TimeoutException timeout)
    {
        // Idle timeouts are handled by HTTP3Stream.
        return false;
    }

    @Override
    public void onFillable()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFillable dataMode={} on {}", parser.isDataMode(), this);
        if (parser.isDataMode())
        {
            // If there are not enough bytes to parse a DATA frame and call
            // the application (so that it can drive), set fill interest.
            processDataFrames(true);
        }
        else
        {
            processNonDataFrames();
        }
    }

    private void processDataFrames(boolean setFillInterest)
    {
        try
        {
            tryAcquireInputBuffer();

            MessageParser.Result result = parseAndFill(setFillInterest);
            switch (result)
            {
                case NO_FRAME -> tryReleaseInputBuffer(false);
                case SWITCH_MODE ->
                {
                    parser.setDataMode(false);
                    processNonDataFrames();
                }
                case FRAME ->
                {
                    action.getAndSet(null).run();
                    // Do not release the buffer before the stream started closing
                    // to avoid races with user-spawned threads that may call Stream.read().
                    if (remotelyClosed)
                    {
                        // The last frame may have caused a write that we need to flush.
                        getEndPoint().getQuicSession().flush();
                        tryReleaseInputBuffer(false);
                    }
                }
            }
        }
        catch (Throwable x)
        {
            tryReleaseInputBuffer(true);
            long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
            getEndPoint().close(error, x);
            // Notify the application that a failure happened.
            parser.getListener().onStreamFailure(getEndPoint().getStreamId(), error, x);
        }
    }

    private void processNonDataFrames()
    {
        try
        {
            tryAcquireInputBuffer();

            while (true)
            {
                MessageParser.Result result = parseAndFill(true);
                switch (result)
                {
                    case NO_FRAME ->
                    {
                        tryReleaseInputBuffer(false);
                        return;
                    }
                    case BLOCKED_FRAME ->
                    {
                        // Return immediately because another thread may
                        // resume the processing as the stream is unblocked.
                        tryReleaseInputBuffer(false);
                        return;
                    }
                    case SWITCH_MODE ->
                    {
                        // MODE_SWITCH is only reported when parsing DATA frames.
                        throw new IllegalStateException();
                    }
                    case FRAME ->
                    {
                        Runnable action = this.action.getAndSet(null);
                        if (action == null)
                            throw new IllegalStateException();
                        action.run();

                        // TODO: we should also exit if the connection was closed due to errors.
                        //  This can be done by overriding relevant methods in MessageListener.

                        if (remotelyClosed)
                        {
                            // We have detected the end of the stream,
                            // do not loop around to parse & fill again.
                            // However, the last frame may have
                            // caused a write that we need to flush.
                            getEndPoint().getQuicSession().flush();
                            tryReleaseInputBuffer(false);
                            return;
                        }

                        if (!parser.isDataMode())
                            continue;

                        if (stream.hasDemandOrStall())
                        {
                            if (inputBuffer != null && inputBuffer.hasRemaining())
                            {
                                // There are bytes left in the buffer; if there are not
                                // enough bytes to parse a DATA frame and call the
                                // application (so that it can drive), set fill interest.
                                processDataFrames(true);
                            }
                            else
                            {
                                // No bytes left in the buffer, but there is demand.
                                // Set fill interest to call the application when bytes arrive.
                                tryReleaseInputBuffer(false);
                                setFillInterest();
                            }
                        }

                        // From now on it's the application that drives
                        // demand, reads, parse+fill and fill interest.
                        return;
                    }
                    default -> throw new IllegalStateException("unknown message parser result: " + result);
                }
            }
        }
        catch (Throwable x)
        {
            tryReleaseInputBuffer(true);
            long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
            getEndPoint().close(error, x);
            // Notify the application that a failure happened.
            parser.getListener().onStreamFailure(getEndPoint().getStreamId(), error, x);
        }
    }

    public void receive()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("receiving on {}", this);
        processDataFrames(false);
    }

    private void tryAcquireInputBuffer()
    {
        if (inputBuffer == null)
        {
            inputBuffer = bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
            if (LOG.isDebugEnabled())
                LOG.debug("acquired {}", inputBuffer);
        }
    }

    private void tryReleaseInputBuffer(boolean force)
    {
        if (inputBuffer != null)
        {
            if (inputBuffer.hasRemaining() && force)
                inputBuffer.clear();
            if (inputBuffer.isEmpty())
            {
                inputBuffer.release();
                if (LOG.isDebugEnabled())
                    LOG.debug("released {}", inputBuffer);
                inputBuffer = null;
            }
        }
    }

    private MessageParser.Result parseAndFill(boolean setFillInterest) throws IOException
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill setFillInterest={} on {} with buffer {}", setFillInterest, this, inputBuffer);

            while (true)
            {
                ByteBuffer byteBuffer = inputBuffer.getByteBuffer();
                MessageParser.Result result = parser.parse(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("parsed {} on {} with buffer {}", result, this, inputBuffer);
                if (result != MessageParser.Result.NO_FRAME)
                    return result;

                if (inputBuffer.isRetained())
                {
                    inputBuffer.release();
                    RetainableByteBuffer newBuffer = bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
                    if (LOG.isDebugEnabled())
                        LOG.debug("reacquired {} for retained {}", newBuffer, inputBuffer);
                    inputBuffer = newBuffer;
                    byteBuffer = inputBuffer.getByteBuffer();
                }

                int filled = fill(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {} with buffer {}", filled, this, inputBuffer);

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

                    if (setFillInterest)
                        setFillInterest();
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

    private void setFillInterest()
    {
        fillInterested(fillableCallback);
    }

    private int fill(ByteBuffer byteBuffer) throws IOException
    {
        return getEndPoint().fill(byteBuffer);
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
        if (frame.isLast())
            shutdownInput();

        Stream.Data data;
        if (!frame.getByteBuffer().hasRemaining() && frame.isLast())
        {
            data = Stream.Data.EOF;
        }
        else
        {
            // No need to call inputBuffer.retain() here, since we know
            // that the action will be run before releasing the inputBuffer.
            data = new StreamData(frame, inputBuffer);
        }

        delegate.run();

        if (LOG.isDebugEnabled())
            LOG.debug("notifying {} on {}", data, stream);
        stream.onData(data);
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
        return String.format("%s[dataMode=%b,stream=%s]", super.toConnectionString(), parser.isDataMode(), stream);
    }

    private static class StreamData extends Stream.Data
    {
        private final RetainableByteBuffer retainable;

        public StreamData(DataFrame frame, RetainableByteBuffer retainable)
        {
            super(frame);
            this.retainable = retainable;
        }

        @Override
        public boolean canRetain()
        {
            return retainable.canRetain();
        }

        @Override
        public boolean isRetained()
        {
            return retainable.isRetained();
        }

        @Override
        public void retain()
        {
            retainable.retain();
        }

        @Override
        public boolean release()
        {
            return retainable.release();
        }
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
            else if (!HTTP3StreamConnection.this.action.compareAndSet(null, action))
                throw new IllegalStateException();
        }

        @Override
        public void onData(long streamId, DataFrame frame)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received {}#{}", frame, streamId);
            Runnable delegate = () -> super.onData(streamId, frame);
            Runnable action = () -> processData(frame, delegate);
            if (!HTTP3StreamConnection.this.action.compareAndSet(null, action))
                throw new IllegalStateException();
        }
    }

    private class FillableCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            onFillable();
        }

        @Override
        public void failed(Throwable x)
        {
            onFillInterestedFailed(x);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.EITHER;
        }
    }
}
