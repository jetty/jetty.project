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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.util.BufferUtil;

public class HeadersBodyParser extends BodyParser
{
    private final HeaderBlockParser headerBlockParser;
    private State state = State.PREPARE;
    private int cursor;
    private int length;
    private int paddingLength;
    private boolean exclusive;
    private int streamId;
    private int weight;

    public HeadersBodyParser(HeaderParser headerParser, Parser.Listener listener, HeaderBlockParser headerBlockParser)
    {
        super(headerParser, listener);
        this.headerBlockParser = headerBlockParser;
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
    }

    @Override
    protected void emptyBody(ByteBuffer buffer)
    {
        MetaData metaData = headerBlockParser.parse(BufferUtil.EMPTY_BUFFER, 0);
        onHeaders(0, 0, false, metaData);
        reset();
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        boolean loop = false;
        while (buffer.hasRemaining() || loop)
        {
            switch (state)
            {
                case PREPARE:
                {
                    // SPEC: wrong streamId is treated as connection error.
                    if (getStreamId() == 0)
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_headers_frame");

                    // For now we don't support HEADERS frames that don't have END_HEADERS.
                    if (!hasFlag(Flags.END_HEADERS))
                        return connectionFailure(buffer, ErrorCode.INTERNAL_ERROR.code, "unsupported_headers_frame");

                    length = getBodyLength();

                    if (isPadding())
                    {
                        state = State.PADDING_LENGTH;
                    }
                    else if (hasFlag(Flags.PRIORITY))
                    {
                        state = State.EXCLUSIVE;
                    }
                    else
                    {
                        state = State.HEADERS;
                    }
                    break;
                }
                case PADDING_LENGTH:
                {
                    paddingLength = buffer.get() & 0xFF;
                    --length;
                    length -= paddingLength;
                    state = hasFlag(Flags.PRIORITY) ? State.EXCLUSIVE : State.HEADERS;
                    loop = length == 0;
                    if (length < 0)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_headers_frame_padding");
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
                            return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_headers_frame");
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
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_headers_frame");
                    if (cursor == 0)
                    {
                        streamId &= 0x7F_FF_FF_FF;
                        state = State.WEIGHT;
                        if (length < 1)
                            return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_headers_frame");
                    }
                    break;
                }
                case WEIGHT:
                {
                    weight = buffer.get() & 0xFF;
                    --length;
                    state = State.HEADERS;
                    loop = length == 0;
                    break;
                }
                case HEADERS:
                {
                    MetaData metaData = headerBlockParser.parse(buffer, length);
                    if (metaData != null)
                    {
                        state = State.PADDING;
                        loop = paddingLength == 0;
                        onHeaders(streamId, weight, exclusive, metaData);
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
                        return true;
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

    private void onHeaders(int streamId, int weight, boolean exclusive, MetaData metaData)
    {
        PriorityFrame priorityFrame = null;
        if (hasFlag(Flags.PRIORITY))
            priorityFrame = new PriorityFrame(streamId, getStreamId(), weight, exclusive);
        HeadersFrame frame = new HeadersFrame(getStreamId(), metaData, priorityFrame, isEndStream());
        notifyHeaders(frame);
    }

    private enum State
    {
        PREPARE, PADDING_LENGTH, EXCLUSIVE, STREAM_ID, STREAM_ID_BYTES, WEIGHT, HEADERS, PADDING
    }
}
