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
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PREPARE:
                {
                    // SPEC: wrong streamId is treated as connection error.
                    if (getStreamId() == 0)
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_rst_stream_frame");
                    int length = getBodyLength();
                    if (length != 4)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_rst_stream_frame");
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
                        return onReset(error);
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

    private boolean onReset(int error)
    {
        ResetFrame frame = new ResetFrame(getStreamId(), error);
        reset();
        notifyReset(frame);
        return true;
    }

    private enum State
    {
        PREPARE, ERROR, ERROR_BYTES
    }
}
