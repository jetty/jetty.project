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

import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>The HTTP/3 protocol parser.</p>
 * <p>This parser makes use of the {@link HeaderParser} and of
 * {@link BodyParser}s to parse HTTP/3 frames.</p>
 */
public class ControlParser
{
    private static final Logger LOG = LoggerFactory.getLogger(ControlParser.class);

    private final HeaderParser headerParser;
    private final BodyParser[] bodyParsers = new BodyParser[FrameType.maxType() + 1];
    private final BodyParser unknownBodyParser;
    private State state = State.HEADER;

    public ControlParser(ParserListener listener)
    {
        this.headerParser = new HeaderParser();
        this.bodyParsers[FrameType.CANCEL_PUSH.type()] = new CancelPushBodyParser(headerParser, listener);
        this.bodyParsers[FrameType.SETTINGS.type()] = new SettingsBodyParser(headerParser, listener);
        this.bodyParsers[FrameType.GOAWAY.type()] = new GoAwayBodyParser(headerParser, listener);
        this.bodyParsers[FrameType.MAX_PUSH_ID.type()] = new MaxPushIdBodyParser(headerParser, listener);
        this.unknownBodyParser = new UnknownBodyParser(headerParser, listener);
    }

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
    }

    /**
     * <p>Parses the given {@code buffer} bytes and emit events to a {@link ParserListener}.</p>
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
                        if (headerParser.parse(buffer))
                        {
                            state = State.BODY;
                            break;
                        }
                        return;
                    }
                    case BODY:
                    {
                        BodyParser bodyParser = null;
                        long frameType = headerParser.getFrameType();
                        if (frameType >= 0 && frameType < bodyParsers.length)
                            bodyParser = bodyParsers[(int)frameType];

                        if (bodyParser == null)
                        {
                            if (FrameType.isMessage(frameType))
                            {
                                // SPEC: message frames on the control stream are invalid.
                                if (LOG.isDebugEnabled())
                                    LOG.debug("invalid message frame type {} on control stream", Long.toHexString(frameType));
                                sessionFailure(buffer, HTTP3ErrorCode.FRAME_UNEXPECTED_ERROR.code(), "invalid_frame_type", new IOException("invalid message frame on control stream"));
                                return;
                            }

                            if (LOG.isDebugEnabled())
                                LOG.debug("ignoring unknown frame type {}", Long.toHexString(frameType));

                            BodyParser.Result result = unknownBodyParser.parse(buffer);
                            if (result == BodyParser.Result.NO_FRAME)
                                return;
                            if (result == BodyParser.Result.WHOLE_FRAME)
                                reset();
                        }
                        else
                        {
                            if (headerParser.getFrameLength() == 0)
                            {
                                bodyParser.emptyBody(buffer);
                                if (LOG.isDebugEnabled())
                                    LOG.debug("parsed {} empty frame body from {}", FrameType.from(frameType), buffer);
                                reset();
                            }
                            else
                            {
                                BodyParser.Result result = bodyParser.parse(buffer);
                                if (result == BodyParser.Result.NO_FRAME)
                                    return;
                                if (LOG.isDebugEnabled())
                                    LOG.debug("parsed {} frame body from {}", FrameType.from(frameType), buffer);
                                if (result == BodyParser.Result.WHOLE_FRAME)
                                    reset();
                            }
                        }
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
                LOG.debug("parse failed", x);
            sessionFailure(buffer, HTTP3ErrorCode.INTERNAL_ERROR.code(), "parser_error", x);
        }
    }

    private void sessionFailure(ByteBuffer buffer, long error, String reason, Throwable failure)
    {
        unknownBodyParser.sessionFailure(buffer, error, reason, failure);
    }

    private enum State
    {
        HEADER, BODY
    }
}
