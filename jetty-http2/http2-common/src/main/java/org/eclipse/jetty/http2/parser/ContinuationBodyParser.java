//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
            onHeaders();
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
                        headerBlockFragments.storeFragment(buffer, remaining, false);
                        length -= remaining;
                        break;
                    }
                    else
                    {
                        boolean last = hasFlag(Flags.END_HEADERS);
                        headerBlockFragments.storeFragment(buffer, length, last);
                        reset();
                        if (last)
                            onHeaders();
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

    private void onHeaders()
    {
        ByteBuffer headerBlock = headerBlockFragments.complete();
        MetaData metaData = headerBlockParser.parse(headerBlock, headerBlock.remaining());
        HeadersFrame frame = new HeadersFrame(getStreamId(), metaData, headerBlockFragments.getPriorityFrame(), headerBlockFragments.isEndStream());
        notifyHeaders(frame);
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
