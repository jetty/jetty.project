//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.http2.ErrorCodes;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.util.BufferUtil;

public class PriorityBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int cursor;
    private boolean exclusive;
    private int streamId;

    public PriorityBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        cursor = 0;
        exclusive = false;
        streamId = 0;
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
                    // SPEC: wrong streamId is treated as connection error.
                    if (getStreamId() == 0)
                    {
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.PROTOCOL_ERROR, "invalid_priority_frame");
                    }
                    int length = getBodyLength();
                    if (length != 5)
                    {
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.FRAME_SIZE_ERROR, "invalid_priority_frame");
                    }
                    state = State.EXCLUSIVE;
                    break;
                }
                case EXCLUSIVE:
                {
                    // We must only peek the first byte and not advance the buffer
                    // because the 31 least significant bits represent the stream id.
                    int currByte = buffer.get(buffer.position());
                    exclusive = (currByte & 0x80) == 0x80;
                    state = State.STREAM_ID;
                    break;
                }
                case STREAM_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        streamId = buffer.getInt();
                        streamId &= 0x7F_FF_FF_FF;
                        state = State.WEIGHT;
                    }
                    else
                    {
                        state = State.STREAM_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case STREAM_ID_BYTES:
                {
                    int currByte = buffer.get() & 0xFF;
                    --cursor;
                    streamId += currByte << (8 * cursor);
                    if (cursor == 0)
                    {
                        streamId &= 0x7F_FF_FF_FF;
                        state = State.WEIGHT;
                    }
                    break;
                }
                case WEIGHT:
                {
                    int weight = buffer.get() & 0xFF;
                    return onPriority(streamId, weight, exclusive);
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return Result.PENDING;
    }

    private Result onPriority(int streamId, int weight, boolean exclusive)
    {
        PriorityFrame frame = new PriorityFrame(streamId, getStreamId(), weight, exclusive);
        reset();
        return notifyPriority(frame) ? Result.ASYNC : Result.COMPLETE;
    }

    private enum State
    {
        PREPARE, EXCLUSIVE, STREAM_ID, STREAM_ID_BYTES, WEIGHT
    }
}
