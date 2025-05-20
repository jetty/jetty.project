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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.hpack.HpackException;

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
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_push_promise_frame");

                    // For now we don't support PUSH_PROMISE frames that don't have END_HEADERS.
                    if (!hasFlag(Flags.END_HEADERS))
                        return connectionFailure(buffer, ErrorCode.INTERNAL_ERROR.code, "unsupported_push_promise_frame");

                    length = getBodyLength();

                    if (isPadding())
                        state = State.PADDING_LENGTH;
                    else
                        state = State.STREAM_ID;
                    break;
                }
                case PADDING_LENGTH:
                {
                    if (length < 1)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_push_promise_frame");
                    paddingLength = buffer.get() & 0xFF;
                    --length;
                    length -= paddingLength;
                    state = State.STREAM_ID;
                    break;
                }
                case STREAM_ID:
                {
                    if (length < 4)
                        return connectionFailure(buffer, ErrorCode.FRAME_SIZE_ERROR.code, "invalid_push_promise_frame");
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
                    int maxLength = headerBlockParser.getMaxHeaderListSize();
                    if (maxLength > 0 && length > maxLength)
                        return connectionFailure(buffer, ErrorCode.REFUSED_STREAM_ERROR.code, "invalid_headers_frame");

                    MetaData metaData = headerBlockParser.parse(buffer, length);

                    // Not enough bytes to parse the MetaData.
                    if (metaData == null)
                        break;

                    Throwable metaDataFailure = MetaData.Failed.getFailure(metaData);
                    if (metaDataFailure instanceof HpackException.SessionException)
                        return false;

                    state = State.PADDING;
                    loop = paddingLength == 0;

                    if (metaDataFailure != null)
                    {
                        PushPromiseFrame frame = new PushPromiseFrame(getStreamId(), streamId, (MetaData.Request)metaData);
                        if (!rateControlOnEvent(frame))
                            return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_push_promise_frame_rate");
                        onPushPromise(frame);
                    }
                    else
                    {
                        onPushPromise((MetaData.Request)metaData);
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

    private void onPushPromise(MetaData.Request metaData)
    {
        PushPromiseFrame frame = new PushPromiseFrame(getStreamId(), streamId, metaData);
        onPushPromise(frame);
    }

    private void onPushPromise(PushPromiseFrame frame)
    {
        notifyPushPromise(frame);
    }

    private enum State
    {
        PREPARE, PADDING_LENGTH, STREAM_ID, STREAM_ID_BYTES, HEADERS, PADDING
    }
}
