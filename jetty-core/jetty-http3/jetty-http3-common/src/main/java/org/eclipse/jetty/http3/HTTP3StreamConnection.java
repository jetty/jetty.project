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
import org.eclipse.jetty.io.Retainable;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HTTP3StreamConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(HTTP3StreamConnection.class);

    private final Callback fillableCallback = new FillableCallback();
    private final AtomicReference<FrameAction> frameAction = new AtomicReference<>();
    private final MessageParser parser;
    private HTTP3Stream stream;
    private org.eclipse.jetty.quic.api.Stream.Data quicData;
    private boolean remotelyClosed;
    private boolean drivesFillInterest = true;

    public HTTP3StreamConnection(StreamEndPoint endPoint, Executor executor, MessageParser parser)
    {
        super(endPoint, executor);
        this.parser = parser;
        parser.init(MessageListener::new);
    }

    @Override
    public StreamEndPoint getEndPoint()
    {
        return (StreamEndPoint)super.getEndPoint();
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
        tryReleaseData(true);
        super.onClose(cause);
    }

    public void onFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFailure on {}", this, failure);
        tryReleaseData(true);
    }

    @Override
    protected boolean onReadTimeout(TimeoutException timeout)
    {
        // Idle timeouts are handled by HTTP3Stream.
        return false;
    }

    @Override
    public void fillInterested()
    {
        fillInterested(fillableCallback);
    }

    @Override
    public void onFillable()
    {
        processFrames(null);
    }

    private void processFrames(ParseResult result)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing frames, drivesFillInterest={} on {}", drivesFillInterest, this);

        try
        {
            if (drivesFillInterest)
            {
                while (true)
                {
                    if (result == null)
                        result = parseAndFill();
                    boolean loop = switch (result)
                    {
                        case NO_FRAME ->
                        {
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

                            tryReleaseData(false);

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
                            yield false;
                        }
                    };

                    if (loop)
                        result = null;
                    else
                        break;
                }
            }
            else
            {
                if (result != null)
                    readData(result);
                stream.processData(true);
            }
        }
        catch (Throwable x)
        {
            tryReleaseData(true);
            long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
            // Notify the application that a failure happened.
            parser.getListener().onStreamFailure(getEndPoint().getStream().getId(), error, x);
            if (stream != null)
                stream.disconnect(error, x, Promise.Invocable.noop());
            else
                getEndPoint().disconnect(error, x, true, Promise.Invocable.noop());
        }
    }

    Stream.Data readData()
    {
        return readData(null);
    }

    private Stream.Data readData(ParseResult result)
    {
        try
        {
            if (remotelyClosed)
                return Stream.Data.EOF;

            if (result == null)
                result = parseAndFill();
            return switch (result)
            {
                case NO_FRAME ->
                {
                    yield null;
                }
                case BLOCKED_FRAME ->
                {
                    // A blocked trailer HEADERS frame.
                    // Return null immediately because another thread may
                    // resume the processing as the stream is unblocked.
                    yield null;
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
                            tryReleaseData(true);
                            yield Stream.Data.EOF;
                        }
                        else
                        {
                            Stream.Data data = new StreamData(dataFrame, quicData);
                            // Retain because multiple data can be parsed from the same QUIC data.
                            data.retain();
                            if (data.isLast())
                                tryReleaseData(true);
                            yield data;
                        }
                    }

                    // It is a trailer HEADERS frame.
                    tryReleaseData(true);
                    yield Stream.Data.EOF;
                }
                case EOF ->
                {
                    yield Stream.Data.EOF;
                }
            };
        }
        catch (IOException x)
        {
            tryReleaseData(true);
            throw new UncheckedIOException(x);
        }
    }

    private ParseResult parseAndFill() throws IOException
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill on {}", this);

            while (true)
            {
                if (quicData != null)
                {
                    MessageParser.Result result = parser.parse(quicData.getByteBuffer(), quicData.isLast());
                    if (LOG.isDebugEnabled())
                        LOG.debug("parsed {} from {} on {}", result, quicData, this);

                    if (result == MessageParser.Result.FRAME)
                        return ParseResult.FRAME;
                    if (result == MessageParser.Result.BLOCKED_FRAME)
                        return ParseResult.BLOCKED_FRAME;

                    tryReleaseData(true);
                }

                quicData = getEndPoint().fill();
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}", quicData, this);

                if (quicData == null)
                    return ParseResult.NO_FRAME;

                if (quicData.getLength() > 0)
                    continue;

                ParseResult result = quicData.isLast() ? ParseResult.EOF : ParseResult.NO_FRAME;
                tryReleaseData(true);
                return result;
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill failure on {}", this, x);
            throw x;
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

    void disconnect(long appErrorCode, Throwable failure, Promise.Invocable<StreamEndPoint> promise)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with error 0x{} {} {}", Long.toHexString(appErrorCode), this, String.valueOf(failure));
        tryReleaseData(true);
        // Propagate outwards.
        getEndPoint().disconnect(appErrorCode, failure, true, promise);
    }

    private void tryReleaseData(boolean force)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("releasing force={} {} on {}", force, quicData, this);
        if (quicData == null)
            return;
        if (force || (quicData.isLast() && !quicData.getByteBuffer().hasRemaining()))
        {
            quicData.release();
            quicData = null;
        }
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s[stream=%s]", super.toConnectionString(), stream);
    }

    private static class StreamData extends Stream.Data
    {
        private final Retainable retainable;

        private StreamData(DataFrame frame, Retainable retainable)
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
            if (!frameAction.compareAndSet(null, new FrameAction(frame, task)))
                throw new IllegalStateException();
            if (wasBlocked)
                processFrames(ParseResult.FRAME);
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
            HTTP3Stream http3Stream = stream;
            return http3Stream == null ? InvocationType.NON_BLOCKING : Invocable.getInvocationType(http3Stream);
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), getInvocationType());
        }
    }
}
