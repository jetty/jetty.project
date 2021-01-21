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

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.ErrorCode;
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
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PREPARE:
                {
                    int length = getBodyLength();
                    if (length != 4)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_window_update_frame");
                    state = State.WINDOW_DELTA;
                    break;
                }
                case WINDOW_DELTA:
                {
                    if (buffer.remaining() >= 4)
                    {
                        windowDelta = buffer.getInt() & 0x7F_FF_FF_FF;
                        return onWindowUpdate(buffer, windowDelta);
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
                        return onWindowUpdate(buffer, windowDelta);
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private boolean onWindowUpdate(ByteBuffer buffer, int windowDelta)
    {
        int streamId = getStreamId();
        if (windowDelta == 0)
        {
            if (streamId == 0)
                return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_window_update_frame");
            else
                return streamFailure(streamId, ErrorCode.PROTOCOL_ERROR.code, "invalid_window_update_frame");
        }
        WindowUpdateFrame frame = new WindowUpdateFrame(streamId, windowDelta);
        reset();
        notifyWindowUpdate(frame);
        return true;
    }

    private enum State
    {
        PREPARE, WINDOW_DELTA, WINDOW_DELTA_BYTES
    }
}
