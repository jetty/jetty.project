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

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.frames.UnknownFrame;
import org.eclipse.jetty.util.buffer.ReadableBuffer;

public class UnknownBodyParser extends BodyParser
{
    private long cursor;

    public UnknownBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    @Override
    public boolean parse(ReadableBuffer buffer)
    {
        long length = cursor == 0 ? getBodyLength() : cursor;
        cursor = consume(buffer, length);
        boolean parsed = cursor == 0;
        if (parsed && !rateControlOnEvent(new UnknownFrame(getFrameType())))
            return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_unknown_frame_rate");
        return parsed;
    }

    private long consume(ReadableBuffer buffer, long length)
    {
        long remaining = buffer.remaining();
        if (remaining >= length)
        {
            buffer.position(buffer.position() + length);
            return 0;
        }
        else
        {
            buffer.position(buffer.position() + remaining);
            return length - remaining;
        }
    }
}
