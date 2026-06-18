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

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.util.buffer.ReadableBuffer;

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
    protected void emptyBody(ReadableBuffer buffer)
    {
        if (isPadding())
        {
            connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_data_frame");
        }
        else
        {
            DataFrame frame = new DataFrame(getStreamId(), ReadableBuffer.EMPTY, isEndStream());
            if (!isEndStream() && !rateControlOnEvent(frame))
                connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_data_frame_rate");
            else
                onData(frame);
            frame.release();
        }
    }

    @Override
    public boolean parse(ReadableBuffer buffer)
    {
        boolean loop = false;
        while (buffer.remaining() > 0L || loop)
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
                    int size = buffer.remaining() > Integer.MAX_VALUE ? length : Math.min((int)buffer.remaining(), length);
                    long position = buffer.position();
                    if (size > buffer.remaining())
                        size = (int)buffer.remaining();
                    ReadableBuffer slice = buffer.slice(position, size);
                    buffer.position(position + size);

                    try
                    {
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
                    }
                    finally
                    {
                        slice.release();
                    }
                    break;
                }
                case PADDING:
                {
                    int size = buffer.remaining() > Integer.MAX_VALUE ? paddingLength : Math.min((int)buffer.remaining(), paddingLength);
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

    private void onData(ReadableBuffer buffer, boolean fragment, int padding)
    {
        DataFrame frame = new DataFrame(getStreamId(), buffer, !fragment && isEndStream(), padding);
        try
        {
            onData(frame);
        }
        finally
        {
            frame.release();
        }
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
