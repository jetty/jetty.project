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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http2.frames.Flag;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;

public class HeadersBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int cursor;
    private int length;
    private int paddingLength;
    private boolean exclusive;
    private int streamId;
    private int weight;
    private HttpFields fields;

    public HeadersBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        cursor = 0;
        length = 0;
        paddingLength = 0;
        exclusive = false;
        streamId = 0;
        weight = 0;
        fields = null;
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
                    length = getBodyLength();
                    fields = new HttpFields();
                    if (isPaddingHigh())
                    {
                        state = State.PADDING_HIGH;
                    }
                    else if (isPaddingLow())
                    {
                        state = State.PADDING_LOW;
                    }
                    else if (hasFlag(Flag.PRIORITY))
                    {
                        state = State.EXCLUSIVE;
                    }
                    else
                    {
                        state = State.HEADERS;
                    }
                    break;
                }
                case PADDING_HIGH:
                {
                    paddingLength = (buffer.get() & 0xFF) << 8;
                    length -= 1;
                    if (length < 1 + 256)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_headers_frame_padding");
                    }
                    state = State.PADDING_LOW;
                    break;
                }
                case PADDING_LOW:
                {
                    paddingLength += buffer.get() & 0xFF;
                    length -= 1;
                    if (length < paddingLength)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_headers_frame_padding");
                    }
                    length -= paddingLength;
                    state = hasFlag(Flag.PRIORITY) ? State.EXCLUSIVE : State.HEADERS;
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
                        length -= 4;
                        state = State.WEIGHT;
                        if (length < 1)
                        {
                            return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_headers_frame");
                        }
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
                    --length;
                    if (cursor > 0 && length <= 0)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_headers_frame");
                    }
                    if (cursor == 0)
                    {
                        streamId &= 0x7F_FF_FF_FF;
                        state = State.WEIGHT;
                        if (length <= 0)
                        {
                            return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_headers_frame");
                        }
                    }
                    break;
                }
                case WEIGHT:
                {
                    weight = buffer.get() & 0xFF;
                    --length;
                    state = State.HEADERS;
                    if (length <= 0)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_headers_frame");
                    }
                    break;
                }
                case HEADERS:
                {
                    // TODO: use HpackDecoder

                    state = State.PADDING;
                    if (onHeaders(streamId, weight, exclusive, fields))
                    {
                        return Result.ASYNC;
                    }
                    break;
                }
                case PADDING:
                {
                    int size = Math.min(buffer.remaining(), paddingLength);
                    buffer.position(buffer.position() + size);
                    paddingLength -= size;
                    if (paddingLength == 0)
                    {
                        reset();
                        return Result.COMPLETE;
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

    private boolean onHeaders(int streamId, int weight, boolean exclusive, HttpFields fields)
    {
        PriorityFrame priorityFrame = null;
        if (hasFlag(Flag.PRIORITY))
        {
            priorityFrame = new PriorityFrame(streamId, getStreamId(), weight, exclusive);
        }
        HeadersFrame frame = new HeadersFrame(getStreamId(), fields, priorityFrame, isEndStream());
        return notifyHeaders(frame);
    }

    private enum State
    {
        PREPARE, PADDING_HIGH, PADDING_LOW, EXCLUSIVE, STREAM_ID, STREAM_ID_BYTES, WEIGHT, HEADERS, PADDING
    }
}
