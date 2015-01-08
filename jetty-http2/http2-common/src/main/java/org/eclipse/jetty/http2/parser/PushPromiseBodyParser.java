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
import org.eclipse.jetty.http2.ErrorCodes;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.util.BufferUtil;

public class PushPromiseBodyParser extends BodyParser
{
    private final HeaderBlockParser headerBlockParser;
    private State state = State.PREPARE;
    private int cursor;
    private int length;
    private int paddingLength;
    private int streamId;

    public PushPromiseBodyParser(HeaderParser headerParser, Parser.Listener listener, HeaderBlockParser headerBlockParser)
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
        streamId = 0;
    }

    @Override
    public Result parse(ByteBuffer buffer)
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
                    {
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.PROTOCOL_ERROR, "invalid_push_promise_frame");
                    }

                    // For now we don't support PUSH_PROMISE frames that don't have END_HEADERS.
                    if (!hasFlag(Flags.END_HEADERS))
                    {
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.INTERNAL_ERROR, "unsupported_push_promise_frame");
                    }

                    length = getBodyLength();

                    if (isPadding())
                    {
                        state = State.PADDING_LENGTH;
                    }
                    else
                    {
                        state = State.STREAM_ID;
                    }
                    break;
                }
                case PADDING_LENGTH:
                {
                    paddingLength = buffer.get() & 0xFF;
                    --length;
                    length -= paddingLength;
                    state = State.STREAM_ID;
                    if (length < 4)
                    {
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.FRAME_SIZE_ERROR, "invalid_push_promise_frame");
                    }
                    break;
                }
                case STREAM_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        streamId = buffer.getInt();
                        streamId &= 0x7F_FF_FF_FF;
                        length -= 4;
                        state = State.HEADERS;
                        loop = length == 0;
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
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.FRAME_SIZE_ERROR, "invalid_push_promise_frame");
                    }
                    if (cursor == 0)
                    {
                        streamId &= 0x7F_FF_FF_FF;
                        state = State.HEADERS;
                        loop = length == 0;
                    }
                    break;
                }
                case HEADERS:
                {
                    MetaData metaData = headerBlockParser.parse(buffer, length);
                    if (metaData != null)
                    {
                        state = State.PADDING;
                        loop = paddingLength == 0;
                        if (onPushPromise(streamId, metaData))
                        {
                            return Result.ASYNC;
                        }
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

    private boolean onPushPromise(int streamId, MetaData metaData)
    {
        PushPromiseFrame frame = new PushPromiseFrame(getStreamId(), streamId, metaData);
        return notifyPushPromise(frame);
    }

    private enum State
    {
        PREPARE, PADDING_LENGTH, STREAM_ID, STREAM_ID_BYTES, HEADERS, PADDING
    }
}
