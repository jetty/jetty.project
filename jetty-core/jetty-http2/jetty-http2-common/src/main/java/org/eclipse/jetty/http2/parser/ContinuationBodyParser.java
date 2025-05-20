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
import org.eclipse.jetty.http2.frames.ContinuationFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.RetainableByteBuffer;

public class ContinuationBodyParser extends BodyParser
{
    private final HeaderBlockParser headerBlockParser;
    private final HeaderBlockFragments headerBlockFragments;
    private State state = State.PREPARE;
    private int length;

    public ContinuationBodyParser(HeaderParser headerParser, Parser.Listener listener, HeaderBlockParser headerBlockParser, HeaderBlockFragments headerBlockFragments)
    {
        super(headerParser, listener);
        this.headerBlockParser = headerBlockParser;
        this.headerBlockFragments = headerBlockFragments;
    }

    @Override
    protected void emptyBody(ByteBuffer buffer)
    {
        if (hasFlag(Flags.END_HEADERS))
        {
            onHeaders(buffer);
        }
        else
        {
            ContinuationFrame frame = new ContinuationFrame(getStreamId(), hasFlag(Flags.END_HEADERS));
            if (!rateControlOnEvent(frame))
                connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_continuation_frame_rate");
        }
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
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_continuation_frame");

                    if (getStreamId() != headerBlockFragments.getStreamId())
                        return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_continuation_stream");

                    length = getBodyLength();
                    state = State.FRAGMENT;
                    break;
                }
                case FRAGMENT:
                {
                    int remaining = buffer.remaining();
                    if (remaining < length)
                    {
                        ContinuationFrame frame = new ContinuationFrame(getStreamId(), false);
                        if (!rateControlOnEvent(frame))
                            return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_continuation_frame_rate");

                        if (!headerBlockFragments.storeFragment(buffer, remaining, false))
                            return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_continuation_stream");

                        length -= remaining;
                        break;
                    }
                    else
                    {
                        boolean endHeaders = hasFlag(Flags.END_HEADERS);
                        ContinuationFrame frame = new ContinuationFrame(getStreamId(), endHeaders);
                        if (!rateControlOnEvent(frame))
                            return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_continuation_frame_rate");

                        if (!headerBlockFragments.storeFragment(buffer, length, endHeaders))
                            return connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_continuation_stream");

                        reset();
                        if (endHeaders)
                            return onHeaders(buffer);
                        return true;
                    }
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private boolean onHeaders(ByteBuffer buffer)
    {
        RetainableByteBuffer headerBlock = headerBlockFragments.complete();
        MetaData metaData = headerBlockParser.parse(headerBlock.getByteBuffer(), headerBlock.remaining());
        headerBlock.release();
        HeadersFrame frame = new HeadersFrame(getStreamId(), metaData, headerBlockFragments.getPriorityFrame(), headerBlockFragments.isEndStream());
        headerBlockFragments.reset();

        Throwable metaDataFailure = MetaData.Failed.getFailure(metaData);
        if (metaDataFailure instanceof HpackException.SessionException)
            return false;

        if (metaDataFailure != null)
        {
            if (!rateControlOnEvent(frame))
                return connectionFailure(buffer, ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, "invalid_headers_frame_rate");
        }
        notifyHeaders(frame);
        return true;
    }

    private void reset()
    {
        state = State.PREPARE;
        length = 0;
    }

    private enum State
    {
        PREPARE, FRAGMENT
    }
}
