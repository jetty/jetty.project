//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http2.frames.ResetFrame;

public class ResetBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int cursor;
    private int error;

    public ResetBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        cursor = 0;
        error = 0;
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PREPARE:
                {
                    int length = getBodyLength();
                    if (length != 4)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_rst_stream_frame");
                    }
                    state = State.ERROR;
                    break;
                }
                case ERROR:
                {
                    if (buffer.remaining() >= 4)
                    {
                        return onReset(buffer.getInt());
                    }
                    else
                    {
                        state = State.ERROR_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case ERROR_BYTES:
                {
                    int currByte = buffer.get() & 0xFF;
                    --cursor;
                    error += currByte << (8 * cursor);
                    if (cursor == 0)
                    {
                        return onReset(error);
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return Result.PENDING;
    }

    private Result onReset(int error)
    {
        ResetFrame frame = new ResetFrame(getStreamId(), error);
        reset();
        return notifyReset(frame) ? Result.ASYNC : Result.COMPLETE;
    }

    private enum State
    {
        PREPARE, ERROR, ERROR_BYTES
    }
}
