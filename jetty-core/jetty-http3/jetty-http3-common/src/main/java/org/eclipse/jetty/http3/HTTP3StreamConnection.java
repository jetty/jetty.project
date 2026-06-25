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

import java.io.UncheckedIOException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.quic.common.StreamEndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
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
    private Content.Chunk quicChunk;
    private boolean remotelyClosed;
    private boolean drivesFillInterest = true;
    private boolean trailerBlocked;

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

    @Override
    public void onFillInterestedFailed(Throwable cause)
    {
        long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
        parser.getListener().onStreamFailure(getEndPoint().getStream().getId(), error, cause);
    }

    private void processFrames(ParseResult result)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("processing frames, drivesFillInterest={} on {}", drivesFillInterest, this);

        try
        {
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
                                // A QPACK-blocked request/response HEADERS frame.
                                // Return immediately, processing will
                                // resume when the stream is QPACK-unblocked.
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

                                // Now the listener drives fill interest via Stream.demand().
                                drivesFillInterest = interim;

                                tryReleaseData(false);

                                // Notify the listener via onRequest()/onResponse().
                                action.task().run();

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
                    {
                        // This is the case of a QPACK-blocked trailer HEADERS frame.
                        // Call read() to notify onTrailer(), then notify
                        // onDataAvailable() so the listener can read EOF.
                        Content.Chunk eof = read(result);
                        assert eof == Content.Chunk.EOF;
                    }
                    // Data was not immediately available, it has just
                    // now been notified to this method from the network.
                    stream.processData(false);
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("failure processing frames on {}", this, x);
                tryReleaseData(true);
                throw x;
            }
        }
        catch (HTTP3Exception.StreamException x)
        {
            parser.getListener().onStreamFailure(getEndPoint().getStream().getId(), x.getErrorCode(), x);
        }
        catch (HTTP3Exception.SessionException x)
        {
            parser.getListener().onSessionFailure(x.getErrorCode(), x.getReason(), x);
        }
        catch (Throwable x)
        {
            // Assume a stream failure, rather than a session failure.
            long error = HTTP3ErrorCode.REQUEST_CANCELLED_ERROR.code();
            parser.getListener().onStreamFailure(getEndPoint().getStream().getId(), error, x);
        }
    }

    Content.Chunk read()
    {
        return read(null);
    }

    private Content.Chunk read(ParseResult result)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("reading, resuming from blocked: {} on {}", result != null, this);

            if (result == null && trailerBlocked)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("reading null, trailer frame blocked on {}", this);
                return null;
            }

            trailerBlocked = false;

            if (remotelyClosed)
                return Content.Chunk.EOF;

            if (result == null)
                result = parseAndFill();

            Content.Chunk chunk = switch (result)
            {
                case NO_FRAME ->
                {
                    yield null;
                }
                case BLOCKED_FRAME ->
                {
                    // A QPACK-blocked trailer HEADERS frame.
                    // Return null until the stream is QPACK-unblocked.
                    trailerBlocked = true;
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
                            yield Content.Chunk.EOF;
                        }
                        else
                        {
                            // Retain because multiple frames can be parsed from the same QUIC chunk.
                            quicChunk.retain();
                            Content.Chunk h3Chunk = Content.Chunk.asChunk(dataFrame.getByteBuffer(), dataFrame.isLast(), quicChunk);
                            if (h3Chunk.isLast())
                                tryReleaseData(true);
                            yield h3Chunk;
                        }
                    }

                    // It is a trailer HEADERS frame.
                    tryReleaseData(true);
                    yield Content.Chunk.EOF;
                }
                case EOF ->
                {
                    yield Content.Chunk.EOF;
                }
            };

            if (LOG.isDebugEnabled())
                LOG.debug("read {} on {}", chunk, this);

            return chunk;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failure reading data on {}", this, x);
            tryReleaseData(true);
            return Content.Chunk.from(x);
        }
    }

    private ParseResult parseAndFill()
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse+fill on {}", this);

            while (true)
            {
                if (quicChunk != null)
                {
                    MessageParser.Result result = parser.parse(quicChunk.getByteBuffer(), quicChunk.isLast());
                    if (LOG.isDebugEnabled())
                        LOG.debug("parsed {} from {} on {}", result, quicChunk, this);

                    if (result == MessageParser.Result.FRAME)
                        return ParseResult.FRAME;
                    if (result == MessageParser.Result.BLOCKED_FRAME)
                        return ParseResult.BLOCKED_FRAME;

                    tryReleaseData(true);
                }

                quicChunk = getEndPoint().fill();
                if (LOG.isDebugEnabled())
                    LOG.debug("filled {} on {}", quicChunk, this);

                if (quicChunk == null)
                    return ParseResult.NO_FRAME;

                if (quicChunk.hasRemaining())
                    continue;

                if (Content.Chunk.isFailure(quicChunk))
                    throw new UncheckedIOException(IO.rethrow(quicChunk.getFailure()));

                ParseResult result = quicChunk.isLast() ? ParseResult.EOF : ParseResult.NO_FRAME;
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

    void disconnect(long appErrorCode, Throwable failure, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("disconnecting with error 0x{} {} {}", Long.toHexString(appErrorCode), this, String.valueOf(failure));
        tryReleaseData(true);
        // Propagate downwards.
        getEndPoint().disconnect(appErrorCode, failure, true, callback);
    }

    private void tryReleaseData(boolean force)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("releasing force={} {} on {}", force, quicChunk, this);
        if (quicChunk == null)
            return;
        if (force || !quicChunk.hasRemaining())
        {
            quicChunk.release();
            quicChunk = null;
        }
    }

    @Override
    public String toConnectionString()
    {
        return String.format("%s[stream=%s]", super.toConnectionString(), stream);
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
            return http3Stream == null ? InvocationType.BLOCKING : Invocable.getInvocationType(http3Stream);
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s]".formatted(TypeUtil.toShortName(getClass()), hashCode(), getInvocationType());
        }
    }
}
