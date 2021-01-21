//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.util.BufferUtil;

public class DataBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int padding;
    private int paddingLength;
    private int length;

    public DataBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        padding = 0;
        paddingLength = 0;
        length = 0;
    }

    @Override
    protected void emptyBody(ByteBuffer buffer)
    {
        if (isPadding())
        {
            connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_data_frame");
        }
        else
        {
            DataFrame frame = new DataFrame(getStreamId(), BufferUtil.EMPTY_BUFFER, isEndStream());
            if (!isEndStream() && !rateControlOnEvent(frame))
                connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_data_frame_rate");
            else
                onData(frame);
        }
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
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_data_frame");

                    length = getBodyLength();
                    state = isPadding() ? State.PADDING_LENGTH : State.DATA;
                    break;
                }
                case PADDING_LENGTH:
                {
                    padding = 1; // We have seen this byte.
                    paddingLength = buffer.get() & 0xFF;
                    --length;
                    length -= paddingLength;
                    state = State.DATA;
                    loop = length == 0;
                    if (length < 0)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_data_frame_padding");
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
                    if (length == 0)
                    {
                        state = State.PADDING;
                        loop = paddingLength == 0;
                        // Padding bytes include the bytes that define the
                        // padding length plus the actual padding bytes.
                        onData(slice, false, padding + paddingLength);
                    }
                    else
                    {
                        // We got partial data, simulate a smaller frame, and stay in DATA state.
                        // No padding for these synthetic frames (even if we have read
                        // the padding length already), it will be accounted at the end.
                        onData(slice, true, 0);
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

    private void onData(ByteBuffer buffer, boolean fragment, int padding)
    {
        onData(new DataFrame(getStreamId(), buffer, !fragment && isEndStream(), padding));
    }

    private void onData(DataFrame frame)
    {
        notifyData(frame);
    }

    private enum State
    {
        PREPARE, PADDING_LENGTH, DATA, PADDING
    }
}
