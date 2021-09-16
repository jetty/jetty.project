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

package org.eclipse.jetty.http3.internal.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.ErrorCode;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
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

    public ControlParser()
    {
        this.headerParser = new HeaderParser();
        this.bodyParsers[FrameType.CANCEL_PUSH.type()] = new CancelPushBodyParser(headerParser);
        this.bodyParsers[FrameType.SETTINGS.type()] = new SettingsBodyParser(headerParser);
        this.bodyParsers[FrameType.GOAWAY.type()] = new GoAwayBodyParser(headerParser);
        this.bodyParsers[FrameType.MAX_PUSH_ID.type()] = new MaxPushIdBodyParser(headerParser);
        this.unknownBodyParser = new UnknownBodyParser(headerParser);
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
    public Frame parse(ByteBuffer buffer) throws ParseException
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
                        return null;
                    }
                    case BODY:
                    {
                        BodyParser bodyParser = null;
                        int frameType = headerParser.getFrameType();
                        if (frameType >= 0 && frameType < bodyParsers.length)
                            bodyParser = bodyParsers[frameType];

                        if (bodyParser == null)
                        {
                            // TODO: enforce only control frames, but ignore unknown.
                            if (LOG.isDebugEnabled())
                                LOG.debug("ignoring unknown frame type {}", Integer.toHexString(frameType));
                            Frame frame = unknownBodyParser.parse(buffer);
                            if (frame == null)
                                return null;
                            reset();
                            break;
                        }
                        else
                        {
                            Frame frame;
                            if (headerParser.getFrameLength() == 0)
                                frame = bodyParser.emptyBody(buffer);
                            else
                                frame = bodyParser.parse(buffer);
                            if (LOG.isDebugEnabled())
                                LOG.debug("parsed {} frame body from {}", FrameType.from(frameType), buffer);
                            if (frame != null)
                                reset();
                            return frame;
                        }
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }
        catch (ParseException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse failed", x);
            buffer.clear();
            throw x;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("parse failed", x);
            buffer.clear();
            throw new ParseException(ErrorCode.INTERNAL_ERROR.code(), "parser_error", true, x);
        }
    }

    private enum State
    {
        HEADER, BODY
    }
}
