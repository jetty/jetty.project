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

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.RateControl;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.frames.WindowUpdateFrame;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.NanoTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The HTTP/2 protocol parser.</p>
 * <p>This parser makes use of the {@link HeaderParser} and of
 * {@link BodyParser}s to parse HTTP/2 frames.</p>
 */
public class Parser
{
    private static final Logger LOG = LoggerFactory.getLogger(Parser.class);

    private final ByteBufferPool bufferPool;
    private final HeaderParser headerParser;
    private final HpackDecoder hpackDecoder;
    private final BodyParser[] bodyParsers;
    private Listener listener;
    private UnknownBodyParser unknownBodyParser;
    private int maxFrameSize = Frame.DEFAULT_MAX_SIZE;
    private int maxSettingsKeys = SettingsFrame.DEFAULT_MAX_KEYS;
    private boolean continuation;
    private State state = State.HEADER;
    private long beginNanoTime;
    private boolean nanoTimeStored;

    public Parser(ByteBufferPool bufferPool, int maxHeaderSize)
    {
        this(bufferPool, maxHeaderSize, RateControl.NO_RATE_CONTROL);
    }

    public Parser(ByteBufferPool bufferPool, int maxHeaderSize, RateControl rateControl)
    {
        this.bufferPool = bufferPool;
        this.headerParser = new HeaderParser(rateControl == null ? RateControl.NO_RATE_CONTROL : rateControl);
        this.hpackDecoder = new HpackDecoder(maxHeaderSize, this::getBeginNanoTime);
        this.bodyParsers = new BodyParser[FrameType.CONTINUATION.ordinal() + 1];
    }

    public void init(Listener listener)
    {
        if (this.listener != null)
            throw new IllegalStateException("Invalid parser initialization");
        this.listener = listener;
        unknownBodyParser = new UnknownBodyParser(headerParser, listener);
        HeaderBlockParser headerBlockParser = new HeaderBlockParser(headerParser, bufferPool, hpackDecoder, unknownBodyParser);
        HeaderBlockFragments headerBlockFragments = new HeaderBlockFragments(bufferPool, hpackDecoder.getMaxHeaderListSize());
        bodyParsers[FrameType.DATA.getType()] = new DataBodyParser(headerParser, listener);
        bodyParsers[FrameType.HEADERS.getType()] = new HeadersBodyParser(headerParser, listener, headerBlockParser, headerBlockFragments);
        bodyParsers[FrameType.PRIORITY.getType()] = new PriorityBodyParser(headerParser, listener);
        bodyParsers[FrameType.RST_STREAM.getType()] = new ResetBodyParser(headerParser, listener);
        bodyParsers[FrameType.SETTINGS.getType()] = new SettingsBodyParser(headerParser, listener, getMaxSettingsKeys());
        bodyParsers[FrameType.PUSH_PROMISE.getType()] = new PushPromiseBodyParser(headerParser, listener, headerBlockParser);
        bodyParsers[FrameType.PING.getType()] = new PingBodyParser(headerParser, listener);
        bodyParsers[FrameType.GO_AWAY.getType()] = new GoAwayBodyParser(headerParser, listener);
        bodyParsers[FrameType.WINDOW_UPDATE.getType()] = new WindowUpdateBodyParser(headerParser, listener);
        bodyParsers[FrameType.CONTINUATION.getType()] = new ContinuationBodyParser(headerParser, listener, headerBlockParser, headerBlockFragments);
    }

    protected Listener getListener()
    {
        return listener;
    }

    public HpackDecoder getHpackDecoder()
    {
        return hpackDecoder;
    }

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
    }

    public long getBeginNanoTime()
    {
        return beginNanoTime;
    }

    private void clearBeginNanoTime()
    {
        nanoTimeStored = false;
    }

    private void storeBeginNanoTime()
    {
        if (!nanoTimeStored)
        {
            nanoTimeStored = true;
            beginNanoTime = NanoTime.now();
        }
    }

    /**
     * <p>Parses the given {@code buffer} bytes and emit events to a {@link Listener}.</p>
     * <p>When this method returns, the buffer may not be fully consumed, so invocations
     * to this method should be wrapped in a loop:</p>
     * <pre>
     * while (buffer.hasRemaining())
     *     parser.parse(buffer);
     * </pre>
     *
     * @param buffer the buffer to parse
     */
    public void parse(ByteBuffer buffer)
    {
        try
        {
            while (true)
            {
                switch (state)
                {
                    case HEADER:
                    {
                        if (buffer.hasRemaining())
                            storeBeginNanoTime();
                        if (!parseHeader(buffer))
                            return;
                        break;
                    }
                    case BODY:
                    {
                        if (!parseBody(buffer))
                            return;
                        if (!continuation)
                            clearBeginNanoTime();
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Parse failed", x);
            connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR, "parser_error");
        }
    }

    protected boolean parseHeader(ByteBuffer buffer)
    {
        if (!headerParser.parse(buffer))
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("Parsed {} frame header from {}@{}", headerParser, buffer, Integer.toHexString(buffer.hashCode()));

        if (headerParser.getLength() > getMaxFrameSize())
            return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR, "invalid_frame_length");

        FrameType frameType = FrameType.from(getFrameType());
        if (continuation)
        {
            // SPEC: CONTINUATION frames must be consecutive.
            if (frameType != FrameType.CONTINUATION)
                return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR, "expected_continuation_frame");
            if (headerParser.hasFlag(Flags.END_HEADERS))
                continuation = false;
        }
        else
        {
            if (frameType == FrameType.HEADERS)
                continuation = !headerParser.hasFlag(Flags.END_HEADERS);
            else if (frameType == FrameType.CONTINUATION)
                return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR, "unexpected_continuation_frame");
        }
        state = State.BODY;
        return true;
    }

    protected boolean parseBody(ByteBuffer buffer)
    {
        int type = getFrameType();
        if (type < 0 || type >= bodyParsers.length)
        {
            // Unknown frame types must be ignored.
            if (LOG.isDebugEnabled())
                LOG.debug("Ignoring unknown frame type {}", Integer.toHexString(type));
            if (!unknownBodyParser.parse(buffer))
                return false;
            reset();
            return true;
        }

        BodyParser bodyParser = bodyParsers[type];
        if (headerParser.getLength() == 0)
        {
            bodyParser.emptyBody(buffer);
        }
        else
        {
            if (!bodyParser.parse(buffer))
                return false;
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Parsed {} frame body from {}@{}", FrameType.from(type), buffer, Integer.toHexString(buffer.hashCode()));
        reset();
        return true;
    }

    private boolean connectionFailure(ByteBuffer buffer, ErrorCode error, String reason)
    {
        return unknownBodyParser.connectionFailure(buffer, error.code, reason);
    }

    protected int getFrameType()
    {
        return headerParser.getFrameType();
    }

    protected boolean hasFlag(int bit)
    {
        return headerParser.hasFlag(bit);
    }

    public int getMaxFrameSize()
    {
        return maxFrameSize;
    }

    public void setMaxFrameSize(int maxFrameSize)
    {
        this.maxFrameSize = maxFrameSize;
    }

    public int getMaxSettingsKeys()
    {
        return maxSettingsKeys;
    }

    public void setMaxSettingsKeys(int maxSettingsKeys)
    {
        this.maxSettingsKeys = maxSettingsKeys;
    }

    protected void notifyConnectionFailure(int error, String reason)
    {
        try
        {
            listener.onConnectionFailure(error, reason);
        }
        catch (Throwable x)
        {
            LOG.info("Failure while notifying listener {}", listener, x);
        }
    }

    public interface Listener
    {
        public default void onData(DataFrame frame)
        {
        }

        public default void onHeaders(HeadersFrame frame)
        {
        }

        public default void onPriority(PriorityFrame frame)
        {
        }

        public default void onReset(ResetFrame frame)
        {
        }

        public default void onSettings(SettingsFrame frame)
        {
        }

        public default void onPushPromise(PushPromiseFrame frame)
        {
        }

        public default void onPing(PingFrame frame)
        {
        }

        public default void onGoAway(GoAwayFrame frame)
        {
        }

        public default void onWindowUpdate(WindowUpdateFrame frame)
        {
        }

        public default void onStreamFailure(int streamId, int error, String reason)
        {
        }

        public default void onConnectionFailure(int error, String reason)
        {
        }

        public static class Wrapper implements Listener
        {
            private final Parser.Listener listener;

            public Wrapper(Parser.Listener listener)
            {
                this.listener = listener;
            }

            public Listener getParserListener()
            {
                return listener;
            }

            @Override
            public void onData(DataFrame frame)
            {
                listener.onData(frame);
            }

            @Override
            public void onHeaders(HeadersFrame frame)
            {
                listener.onHeaders(frame);
            }

            @Override
            public void onPriority(PriorityFrame frame)
            {
                listener.onPriority(frame);
            }

            @Override
            public void onReset(ResetFrame frame)
            {
                listener.onReset(frame);
            }

            @Override
            public void onSettings(SettingsFrame frame)
            {
                listener.onSettings(frame);
            }

            @Override
            public void onPushPromise(PushPromiseFrame frame)
            {
                listener.onPushPromise(frame);
            }

            @Override
            public void onPing(PingFrame frame)
            {
                listener.onPing(frame);
            }

            @Override
            public void onGoAway(GoAwayFrame frame)
            {
                listener.onGoAway(frame);
            }

            @Override
            public void onWindowUpdate(WindowUpdateFrame frame)
            {
                listener.onWindowUpdate(frame);
            }

            @Override
            public void onStreamFailure(int streamId, int error, String reason)
            {
                listener.onStreamFailure(streamId, error, reason);
            }

            @Override
            public void onConnectionFailure(int error, String reason)
            {
                listener.onConnectionFailure(error, reason);
            }
        }
    }

    private enum State
    {
        HEADER, BODY
    }
}
