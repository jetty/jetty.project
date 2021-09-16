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

/**
 * <p>The base parser for the frame body of HTTP/3 frames.</p>
 * <p>Subclasses implement {@link #parse(ByteBuffer)} to parse
 * the frame specific body.</p>
 *
 * @see MessageParser
 */
public abstract class BodyParser
{
    private final long streamId;
    private final HeaderParser headerParser;

    protected BodyParser(long streamId, HeaderParser headerParser)
    {
        this.streamId = streamId;
        this.headerParser = headerParser;
    }

    protected long getStreamId()
    {
        return streamId;
    }

    protected long getBodyLength()
    {
        return headerParser.getFrameLength();
    }

    /**
     * <p>Parses the frame body bytes in the given {@code buffer}, producing a {@link Frame}.</p>
     * <p>Only the frame body bytes are consumed, therefore when this method returns, the buffer
     * may contain unconsumed bytes, for example for other frames.</p>
     *
     * @param buffer the buffer to parse
     * @return the parsed frame if all the frame body bytes were parsed, or an error frame,
     * or null if not enough frame body bytes were present in the buffer
     */
    public abstract Frame parse(ByteBuffer buffer) throws ParseException;

    protected Frame emptyBody(ByteBuffer buffer) throws ParseException
    {
        throw new ParseException(ErrorCode.PROTOCOL_ERROR.code(), "invalid_frame");
    }
}
