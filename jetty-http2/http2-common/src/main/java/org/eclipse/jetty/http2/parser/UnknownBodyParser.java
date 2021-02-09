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

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.frames.UnknownFrame;

public class UnknownBodyParser extends BodyParser
{
    private int cursor;

    public UnknownBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        int length = cursor == 0 ? getBodyLength() : cursor;
        cursor = consume(buffer, length);
        boolean parsed = cursor == 0;
        if (parsed && !rateControlOnEvent(new UnknownFrame(getFrameType())))
            return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_unknown_frame_rate");

        return parsed;
    }

    private int consume(ByteBuffer buffer, int length)
    {
        int remaining = buffer.remaining();
        if (remaining >= length)
        {
            buffer.position(buffer.position() + length);
            return 0;
        }
        else
        {
            buffer.position(buffer.limit());
            return length - remaining;
        }
    }
}
