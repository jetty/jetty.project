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
import org.eclipse.jetty.http3.qpack.QpackDecoder;
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

    private final HeaderParser headerParser;
    private final BodyParser[] bodyParsers = new BodyParser[FrameType.maxType() + 1];
    private final BodyParser unknownBodyParser;
    private State state = State.HEADER;

    public MessageParser(long streamId, QpackDecoder decoder)
    {
        this.headerParser = new HeaderParser();
        this.bodyParsers[FrameType.DATA.type()] = new DataBodyParser(streamId, headerParser);
        this.bodyParsers[FrameType.HEADERS.type()] = new HeadersBodyParser(streamId, headerParser, decoder);
        this.bodyParsers[FrameType.PUSH_PROMISE.type()] = new PushPromiseBodyParser(headerParser);
        this.unknownBodyParser = new UnknownBodyParser(headerParser);
    }

    private void reset()
    {
        headerParser.reset();
        state = State.HEADER;
    }

    /**
     * <p>Parses the given {@code buffer} bytes and returns parsed frames.</p>
     *
     * @param buffer the buffer to parse
     * @return a parsed frame, or null if not enough bytes were provided to parse a frame
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
                            // Unknown frame types must be ignored.
                            if (LOG.isDebugEnabled())
                                LOG.debug("Ignoring unknown frame type {}", Integer.toHexString(frameType));
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
                            if (frame != null)
                            {
                                if (LOG.isDebugEnabled())
                                    LOG.debug("Parsed {} frame body from {}", FrameType.from(frameType), buffer);
                                reset();
                            }
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
