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

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.util.BufferUtil;

public class DataBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int paddingLength;
    private int length;

    public DataBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        paddingLength = 0;
        length = 0;
    }

    @Override
    protected boolean emptyBody()
    {
        if (isPaddingHigh() || isPaddingLow())
        {
            notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_data_frame");
            return false;
        }
        return onData(BufferUtil.EMPTY_BUFFER, false, 0);
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
                    length = getBodyLength();
                    if (isPaddingHigh())
                    {
                        state = State.PADDING_HIGH;
                    }
                    else if (isPaddingLow())
                    {
                        state = State.PADDING_LOW;
                    }
                    else
                    {
                        state = State.DATA;
                    }
                    break;
                }
                case PADDING_HIGH:
                {
                    paddingLength = (buffer.get() & 0xFF) << 8;
                    --length;
                    if (length < 1 + 256)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_data_frame_padding");
                    }
                    state = State.PADDING_LOW;
                    break;
                }
                case PADDING_LOW:
                {
                    paddingLength += buffer.get() & 0xFF;
                    --length;
                    length -= paddingLength;
                    state = State.DATA;
                    loop = length == 0;
                    if (length < 0)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_data_frame_padding");
                    }
                    break;
                }
                case DATA:
                {
                    int size = Math.min(buffer.remaining(), length);
                    int position = buffer.position();
                    int limit = buffer.limit();
                    buffer.limit(position + size);
                    ByteBuffer slice = buffer.slice();
                    buffer.limit(limit);
                    buffer.position(position + size);

                    length -= size;
                    loop = paddingLength == 0;
                    if (length == 0)
                    {
                        state = State.PADDING;
                        if (onData(slice, false, paddingLength))
                        {
                            return Result.ASYNC;
                        }
                    }
                    else
                    {
                        // TODO: check the semantic of Flag.END_SEGMENT.
                        // We got partial data, simulate a smaller frame, and stay in DATA state.
                        if (onData(slice, true, 0))
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

    private boolean onData(ByteBuffer buffer, boolean fragment, int padding)
    {
        DataFrame frame = new DataFrame(getStreamId(), buffer, !fragment && isEndStream(), padding);
        return notifyData(frame);
    }

    private enum State
    {
        PREPARE, PADDING_HIGH, PADDING_LOW, DATA, PADDING
    }
}
