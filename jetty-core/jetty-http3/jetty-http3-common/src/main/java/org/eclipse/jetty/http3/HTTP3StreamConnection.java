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
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3StreamConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3StreamConnection.class);

    private final AtomicReference<FrameAction> frameAction = new AtomicReference<>();
    private final ByteBufferPool bufferPool;
    private final int minInputBufferSpace;
    private final MessageParser parser;
    private boolean useInputDirectByteBuffers = true;
    private HTTP3Stream stream;
    private RetainableByteBuffer inputBuffer;
    private boolean remotelyClosed;
    private boolean drivesFillInterest = true;

    public HTTP3StreamConnection(StreamEndPoint endPoint, Executor executor, ByteBufferPool bufferPool, MessageParser parser)
    {
        this(endPoint, executor, bufferPool, parser, -1);
    }

    public HTTP3StreamConnection(StreamEndPoint endPoint, Executor executor, ByteBufferPool bufferPool, MessageParser parser, int minInputBufferSpace)
    {
        super(endPoint, executor);
        this.bufferPool = bufferPool;
        this.parser = parser;
        parser.init(MessageListener::new);
        this.minInputBufferSpace = minInputBufferSpace < 0 ? 1500 : minInputBufferSpace;
    }

    public void onFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFailure on {}", this, failure);
        tryReleaseInputBuffer(true);
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

    void setStream(HTTP3Stream stream)
    {
        this.stream = stream;
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
        tryReleaseInputBuffer(true);
        super.onClose(cause);
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
            LOG.debug("onFillable drivesFillInterest={} on {}", drivesFillInterest, this);

        try
        {
            if (drivesFillInterest)
            {
                tryAcquireInputBuffer();
                while (true)
                {
                    ParseResult result = parseAndFill();
                    boolean loop = switch (result)
                    {
                        case NO_FRAME ->
                        {
                            tryReleaseInputBuffer(false);
                            fillInterested();
                            yield false;
                        }
                        case BLOCKED_FRAME ->
                        {
                            // Return immediately because another thread may
                            // resume the processing as the stream is unblocked.
                            yield false;
                        }
                        case FRAME ->
                        {
                            FrameAction action = frameAction.getAndSet(null);

                            boolean interim = false;
                            if (action.frame() instanceof HeadersFrame headers)
                            {
                                MetaData metaData = headers.getMetaData();
                                if (metaData instanceof MetaData.Response response)
                                    interim = HttpStatus.isInterim(response.getStatus());
                            }

                            // Now the application drives fill interest via Stream.demand().
                            drivesFillInterest = interim;

                            // Release the buffer before notifying the application,
                            // because the application may concurrently call readData().
                            if (!interim)
                                tryReleaseInputBuffer(false);

                            // Notify the application via onRequest()/onResponse().
                            action.task().run();

                            // Notify onDataAvailable() if the application
                            // demanded in onRequest()/onResponse().
                            if (!interim)
                                stream.processData(false);

                            yield interim;
                        }
                        case EOF ->
                        {
                            tryReleaseInputBuffer(true);
                            yield false;
                        }
                    };
                    if (!loop)
                        break;
                }
            }
            else
            {
                stream.processData(true);
            }
        }
        catch (Throwable x)
        {
            tryReleaseInputBuffer(true);
            long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
            // Notify the application that a failure happened.
            parser.getListener().onStreamFailure(getEndPoint().getStream().getId(), error, x);
            if (stream != null)
                stream.disconnect(error, x);
            else
                getEndPoint().disconnect(error, x, true);
        }
    }

    Stream.Data readData()
    {
        try
        {
            if (remotelyClosed)
                return Stream.Data.EOF;

            tryAcquireInputBuffer();
            ParseResult result = parseAndFill();
            return switch (result)
            {
                case NO_FRAME ->
                {
                    if (!inputBuffer.isRetained())
                        tryReleaseInputBuffer(false);
                    yield null;
                }
                case BLOCKED_FRAME ->
                {
                    // A blocked trailer HEADERS frame.
                    // Return EOF immediately because another thread may
                    // resume the processing as the stream is unblocked.
                    yield Stream.Data.EOF;
                }
                case FRAME ->
                {
                    FrameAction action = frameAction.getAndSet(null);
                    action.task().run();

                    Frame frame = action.frame();
                    if (frame instanceof DataFrame dataFrame)
                    {
                        if (dataFrame.isLast() && !dataFrame.getByteBuffer().hasRemaining())
                        {
                            tryReleaseInputBuffer(false);
                            yield Stream.Data.EOF;
                        }
                        else
                        {
                            Stream.Data data = new StreamData(dataFrame, inputBuffer);
                            // Retain because multiple data can be parsed from the same inputBuffer.
                            data.retain();
                            // Try to reuse the inputBuffer if it's not the last data.
                            if (data.isLast())
                                tryReleaseInputBuffer(false);
                            yield data;
                        }
                    }

                    // It is a trailer HEADERS frame.
                    tryReleaseInputBuffer(true);
                    yield Stream.Data.EOF;
                }
                case EOF ->
                {
                    tryReleaseInputBuffer(true);
                    yield Stream.Data.EOF;
                }
            };
        }
        catch (IOException x)
        {
            tryReleaseInputBuffer(true);
            throw new UncheckedIOException(x);
        }
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
                boolean released = inputBuffer.release();
                if (LOG.isDebugEnabled())
                    LOG.debug("released {} {}", released, inputBuffer);
                inputBuffer = null;
            }
        }
    }

    private ParseResult parseAndFill() throws IOException
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill on {} with buffer {}", this, inputBuffer);

            while (true)
            {
                ByteBuffer byteBuffer = inputBuffer.getByteBuffer();
                MessageParser.Result result = parser.parse(byteBuffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("parsed {} on {} with buffer {}", result, this, inputBuffer);
                if (result == MessageParser.Result.FRAME)
                    return ParseResult.FRAME;
                if (result == MessageParser.Result.BLOCKED_FRAME)
                    return ParseResult.BLOCKED_FRAME;

                boolean compact = true;
                if (inputBuffer.isRetained())
                {
                    // If there is sufficient space available, we can top up the buffer rather than allocate a new one
                    if (minInputBufferSpace > 0 && BufferUtil.space(inputBuffer.getByteBuffer()) >= minInputBufferSpace)
                    {
                        // Do not compact the buffer.
                        compact = false;
                    }
                    else
                    {
                        inputBuffer.release();
                        RetainableByteBuffer newBuffer = bufferPool.acquire(getInputBufferSize(), isUseInputDirectByteBuffers());
                        if (LOG.isDebugEnabled())
                            LOG.debug("reacquired {} for retained {}", newBuffer, inputBuffer);
                        inputBuffer = newBuffer;
                        byteBuffer = inputBuffer.getByteBuffer();
                    }
                }

                int filled = fill(byteBuffer, compact);
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {} with buffer {}", filled, this, inputBuffer);

                if (filled > 0)
                    continue;

                if (filled == 0)
                {
                    // Workaround for a Quiche glitch, that sometimes reports
                    // an HTTP/3 frame with last=false, but a subsequent read
                    // of zero bytes reports that the stream is finished.
                    boolean quicRemotelyClosed = getEndPoint().getStream().isRemotelyClosed();
                    if (!remotelyClosed && quicRemotelyClosed)
                    {
                        // An empty HTTP/3 DATA frame is the sequence of bytes [0x0, 0x0].
                        ByteBuffer emptyDataFrame = ByteBuffer.allocate(2);
                        parser.parse(emptyDataFrame);
                        return ParseResult.FRAME;
                    }
                    return ParseResult.NO_FRAME;
                }

                return ParseResult.EOF;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill failure on {}", this, x);
            throw x;
        }
    }

    private int fill(ByteBuffer buffer, boolean compact) throws IOException
    {
        int padding = 0;
        try
        {
            if (!compact)
            {
                // Add padding content to avoid compaction
                padding = buffer.limit();
                buffer.position(0);
            }
            return getEndPoint().fill(buffer);
        }
        finally
        {
            if (!compact && padding > 0)
                buffer.position(padding);
        }
    }

    private void processHeaders(HeadersFrame frame, boolean wasBlocked, Runnable delegate)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} wasBlocked={} on {}", frame, wasBlocked, this);

        MetaData metaData = frame.getMetaData();
        if (!metaData.isRequest() && !metaData.isResponse())
        {
            // Trailer.
            if (!frame.isLast())
                frame = new HeadersFrame(metaData, true);
        }

        if (frame.isLast())
            shutdownInput();

        delegate.run();
    }

    private void processData(DataFrame frame, Runnable delegate)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing {} on {}", frame, this);

        if (frame.isLast())
            shutdownInput();

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

    CompletableFuture<StreamEndPoint> disconnect(long appErrorCode, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with error 0x{} {} {}", Long.toHexString(appErrorCode), this, String.valueOf(failure));
        tryReleaseInputBuffer(true);
        // Propagate outwards.
        return getEndPoint().disconnect(appErrorCode, failure, true);
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s[stream=%s]", super.toConnectionString(), stream);
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

        @Override
        public String toString()
        {
            return "%s[%s]".formatted(super.toString(), retainable);
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
            Runnable task = () -> processHeaders(frame, wasBlocked, delegate);
            if (wasBlocked)
                task.run();
            else if (!frameAction.compareAndSet(null, new FrameAction(frame, task)))
                throw new IllegalStateException();
        }

        @Override
        public void onData(long streamId, DataFrame frame)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("received {}#{}", frame, streamId);
            Runnable delegate = () -> super.onData(streamId, frame);
            Runnable task = () -> processData(frame, delegate);
            if (!frameAction.compareAndSet(null, new FrameAction(frame, task)))
                throw new IllegalStateException();
        }
    }

    private enum ParseResult
    {
        NO_FRAME,
        BLOCKED_FRAME,
        FRAME,
        EOF
    }

    private record FrameAction(Frame frame, Runnable task)
    {
    }
}
