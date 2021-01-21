//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.Flags;
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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>The HTTP/2 protocol parser.</p>
 * <p>This parser makes use of the {@link HeaderParser} and of
 * {@link BodyParser}s to parse HTTP/2 frames.</p>
 */
public class Parser
{
    private static final Logger LOG = Log.getLogger(Parser.class);

    private final ByteBufferPool byteBufferPool;
    private final Listener listener;
    private final HeaderParser headerParser;
    private final HpackDecoder hpackDecoder;
    private final BodyParser[] bodyParsers;
    private UnknownBodyParser unknownBodyParser;
    private int maxFrameLength = Frame.DEFAULT_MAX_LENGTH;
    private int maxSettingsKeys = SettingsFrame.DEFAULT_MAX_KEYS;
    private boolean continuation;
    private State state = State.HEADER;

    public Parser(ByteBufferPool byteBufferPool, Listener listener, int maxDynamicTableSize, int maxHeaderSize)
    {
        this(byteBufferPool, listener, maxDynamicTableSize, maxHeaderSize, RateControl.NO_RATE_CONTROL);
    }

    public Parser(ByteBufferPool byteBufferPool, Listener listener, int maxDynamicTableSize, int maxHeaderSize, RateControl rateControl)
    {
        this.byteBufferPool = byteBufferPool;
        this.listener = listener;
        this.headerParser = new HeaderParser(rateControl == null ? RateControl.NO_RATE_CONTROL : rateControl);
        this.hpackDecoder = new HpackDecoder(maxDynamicTableSize, maxHeaderSize);
        this.bodyParsers = new BodyParser[FrameType.values().length];
    }

    public void init(UnaryOperator<Listener> wrapper)
    {
        Listener listener = wrapper.apply(this.listener);
        unknownBodyParser = new UnknownBodyParser(headerParser, listener);
        HeaderBlockParser headerBlockParser = new HeaderBlockParser(headerParser, byteBufferPool, hpackDecoder, unknownBodyParser);
        HeaderBlockFragments headerBlockFragments = new HeaderBlockFragments();
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

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
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
                        if (!parseHeader(buffer))
                            return;
                        break;
                    }
                    case BODY:
                    {
                        if (!parseBody(buffer))
                            return;
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
                LOG.debug(x);
            connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR, "parser_error");
        }
    }

    protected boolean parseHeader(ByteBuffer buffer)
    {
        if (!headerParser.parse(buffer))
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("Parsed {} frame header from {}@{}", headerParser, buffer, Integer.toHexString(buffer.hashCode()));

        if (headerParser.getLength() > getMaxFrameLength())
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

    public int getMaxFrameLength()
    {
        return maxFrameLength;
    }

    public void setMaxFrameLength(int maxFrameLength)
    {
        this.maxFrameLength = maxFrameLength;
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
            LOG.info("Failure while notifying listener " + listener, x);
        }
    }

    public interface Listener
    {
        void onData(DataFrame frame);

        void onHeaders(HeadersFrame frame);

        void onPriority(PriorityFrame frame);

        void onReset(ResetFrame frame);

        void onSettings(SettingsFrame frame);

        void onPushPromise(PushPromiseFrame frame);

        void onPing(PingFrame frame);

        void onGoAway(GoAwayFrame frame);

        void onWindowUpdate(WindowUpdateFrame frame);

        void onStreamFailure(int streamId, int error, String reason);

        void onConnectionFailure(int error, String reason);

        class Adapter implements Listener
        {
            @Override
            public void onData(DataFrame frame)
            {
            }

            @Override
            public void onHeaders(HeadersFrame frame)
            {
            }

            @Override
            public void onPriority(PriorityFrame frame)
            {
            }

            @Override
            public void onReset(ResetFrame frame)
            {
            }

            @Override
            public void onSettings(SettingsFrame frame)
            {
            }

            @Override
            public void onPushPromise(PushPromiseFrame frame)
            {
            }

            @Override
            public void onPing(PingFrame frame)
            {
            }

            @Override
            public void onGoAway(GoAwayFrame frame)
            {
            }

            @Override
            public void onWindowUpdate(WindowUpdateFrame frame)
            {
            }

            @Override
            public void onStreamFailure(int streamId, int error, String reason)
            {
            }

            @Override
            public void onConnectionFailure(int error, String reason)
            {
                LOG.warn("Connection failure: {}/{}", error, reason);
            }
        }

        class Wrapper implements Listener
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
