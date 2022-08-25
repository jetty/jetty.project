//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal.parser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The HTTP/3 protocol parser.</p>
 * <p>This parser makes use of the {@link HeaderParser} and of
 * {@link BodyParser}s to parse HTTP/3 frames.</p>
 */
public class MessageParser
{
    private static final Logger LOG = LoggerFactory.getLogger(MessageParser.class);

    private final HeaderParser headerParser = new HeaderParser();
    private final BodyParser[] bodyParsers = new BodyParser[FrameType.maxType() + 1];
    private final ParserListener listener;
    private final QpackDecoder decoder;
    private final long streamId;
    private final BooleanSupplier isLast;
    private BodyParser unknownBodyParser;
    private State state = State.HEADER;
    protected boolean dataMode;

    public MessageParser(ParserListener listener, QpackDecoder decoder, long streamId, BooleanSupplier isLast)
    {
        this.listener = listener;
        this.decoder = decoder;
        this.streamId = streamId;
        this.isLast = isLast;
    }

    public void init(UnaryOperator<ParserListener> wrapper)
    {
        ParserListener listener = wrapper.apply(this.listener);
        this.bodyParsers[FrameType.DATA.type()] = new DataBodyParser(headerParser, listener, streamId, isLast);
        this.bodyParsers[FrameType.HEADERS.type()] = new HeadersBodyParser(headerParser, listener, decoder, streamId, isLast);
        this.bodyParsers[FrameType.PUSH_PROMISE.type()] = new PushPromiseBodyParser(headerParser, listener);
        this.unknownBodyParser = new UnknownBodyParser(headerParser, listener);
    }

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
    }

    public ParserListener getListener()
    {
        return listener;
    }

    public void setDataMode(boolean enable)
    {
        this.dataMode = enable;
    }

    /**
     * <p>Parses the given {@code buffer} bytes and emit events to a {@link ParserListener}.</p>
     * <p>Only the bytes of one frame are consumed, therefore when this method returns,
     * the buffer may contain unconsumed bytes, for example for other frames.</p>
     *
     * @param buffer the buffer to parse
     * @return the result of the parsing
     */
    public Result parse(ByteBuffer buffer)
    {
        try
        {
            while (true)
            {
                switch (state)
                {
                    case HEADER ->
                    {
                        if (headerParser.parse(buffer))
                        {
                            state = State.BODY;
                            // If we are in data mode, but we did not parse a DATA frame, bail out.
                            if (dataMode && headerParser.getFrameType() != FrameType.DATA.type())
                                return Result.MODE_SWITCH;
                        }
                        else
                        {
                            return Result.NO_FRAME;
                        }
                    }
                    case BODY ->
                    {
                        BodyParser bodyParser = null;
                        long frameType = headerParser.getFrameType();
                        if (frameType >= 0 && frameType < bodyParsers.length)
                            bodyParser = bodyParsers[(int)frameType];

                        if (bodyParser == null)
                        {
                            if (FrameType.isControl(frameType))
                            {
                                // SPEC: control frames on a message stream are invalid.
                                if (LOG.isDebugEnabled())
                                    LOG.debug("invalid control frame type {} on message stream", Long.toHexString(frameType));
                                sessionFailure(buffer, HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_type", new IOException("invalid control frame in message stream"));
                                return Result.NO_FRAME;
                            }

                            if (LOG.isDebugEnabled())
                                LOG.debug("ignoring unknown frame type {}", Long.toHexString(frameType));

                            BodyParser.Result result = unknownBodyParser.parse(buffer);
                            if (result == BodyParser.Result.NO_FRAME)
                                return Result.NO_FRAME;
                            if (LOG.isDebugEnabled())
                                LOG.debug("parsed unknown frame body for type {}", Long.toHexString(frameType));
                            if (result == BodyParser.Result.WHOLE_FRAME)
                                reset();
                        }
                        else
                        {
                            if (headerParser.getFrameLength() == 0)
                            {
                                bodyParser.emptyBody(buffer);
                                if (LOG.isDebugEnabled())
                                    LOG.debug("parsed {} empty frame body from {}", FrameType.from(frameType), BufferUtil.toDetailString(buffer));
                                reset();
                            }
                            else
                            {
                                BodyParser.Result result = bodyParser.parse(buffer);
                                if (result == BodyParser.Result.NO_FRAME)
                                    return Result.NO_FRAME;
                                if (LOG.isDebugEnabled())
                                    LOG.debug("parsed {} frame body from {}", FrameType.from(frameType), BufferUtil.toDetailString(buffer));
                                if (result == BodyParser.Result.WHOLE_FRAME)
                                    reset();
                            }
                            return Result.FRAME;
                        }
                    }
                    default -> throw new IllegalStateException();
                }
            }
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse failed", x);
            sessionFailure(buffer, HTTP3ErrorCode.INTERNAL_ERROR.code(), "parser_error", x);
            return Result.NO_FRAME;
        }
    }

    private void sessionFailure(ByteBuffer buffer, long error, String reason, Throwable failure)
    {
        unknownBodyParser.sessionFailure(buffer, error, reason, failure);
    }

    public enum Result
    {
        NO_FRAME, FRAME, MODE_SWITCH
    }

    private enum State
    {
        HEADER, BODY
    }
}
