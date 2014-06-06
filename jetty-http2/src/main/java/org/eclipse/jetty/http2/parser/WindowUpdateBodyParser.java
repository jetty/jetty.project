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

import org.eclipse.jetty.http2.frames.WindowUpdateFrame;

public class WindowUpdateBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int cursor;
    private int windowDelta;

    public WindowUpdateBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        cursor = 0;
        windowDelta = 0;
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
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_window_update_frame");
                    }
                    state = State.WINDOW_DELTA;
                    break;
                }
                case WINDOW_DELTA:
                {
                    if (buffer.remaining() >= 4)
                    {
                        windowDelta = buffer.getInt() & 0x7F_FF_FF_FF;
                        return onWindowUpdate(windowDelta);
                    }
                    else
                    {
                        state = State.WINDOW_DELTA_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case WINDOW_DELTA_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    windowDelta += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        windowDelta &= 0x7F_FF_FF_FF;
                        return onWindowUpdate(windowDelta);
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

    private Result onWindowUpdate(int windowDelta)
    {
        WindowUpdateFrame frame = new WindowUpdateFrame(getStreamId(), windowDelta);
        reset();
        return notifyWindowUpdate(frame) ? Result.ASYNC : Result.COMPLETE;
    }

    private enum State
    {
        PREPARE, WINDOW_DELTA, WINDOW_DELTA_BYTES
    }
}
