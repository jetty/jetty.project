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

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.frames.PriorityFrame;

public class PriorityBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int cursor;
    private boolean exclusive;
    private int parentStreamId;

    public PriorityBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        cursor = 0;
        exclusive = false;
        parentStreamId = 0;
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
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_priority_frame");
                    int length = getBodyLength();
                    if (length != 5)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_priority_frame");
                    state = State.EXCLUSIVE;
                    break;
                }
                case EXCLUSIVE:
                {
                    // We must only peek the first byte and not advance the buffer
                    // because the 31 least significant bits represent the stream id.
                    int currByte = buffer.get(buffer.position());
                    exclusive = (currByte & 0x80) == 0x80;
                    state = State.PARENT_STREAM_ID;
                    break;
                }
                case PARENT_STREAM_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        parentStreamId = buffer.getInt();
                        parentStreamId &= 0x7F_FF_FF_FF;
                        state = State.WEIGHT;
                    }
                    else
                    {
                        state = State.PARENT_STREAM_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case PARENT_STREAM_ID_BYTES:
                {
                    int currByte = buffer.get() & 0xFF;
                    --cursor;
                    parentStreamId += currByte << (8 * cursor);
                    if (cursor == 0)
                    {
                        parentStreamId &= 0x7F_FF_FF_FF;
                        state = State.WEIGHT;
                    }
                    break;
                }
                case WEIGHT:
                {
                    // SPEC: stream cannot depend on itself.
                    if (getStreamId() == parentStreamId)
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_priority_frame");
                    // SPEC: for RFC 7540 weight is 1..256, for RFC 9113 is an unused value.
                    int weight = (buffer.get() & 0xFF) + 1;
                    return onPriority(buffer, parentStreamId, weight, exclusive);
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private boolean onPriority(ByteBuffer buffer, int parentStreamId, int weight, boolean exclusive)
    {
        PriorityFrame frame = new PriorityFrame(getStreamId(), parentStreamId, weight, exclusive);
        if (!rateControlOnEvent(frame))
            return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_priority_frame_rate");
        reset();
        notifyPriority(frame);
        return true;
    }

    private enum State
    {
        PREPARE, EXCLUSIVE, PARENT_STREAM_ID, PARENT_STREAM_ID_BYTES, WEIGHT
    }
}
