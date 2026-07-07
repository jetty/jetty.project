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

package org.eclipse.jetty.http3.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DataBodyParser extends BodyParser
{
    private static final Logger LOG = LoggerFactory.getLogger(DataBodyParser.class);

    private final long streamId;
    private State state = State.INIT;
    private long length;

    public DataBodyParser(HeaderParser headerParser, ParserListener listener, long streamId)
    {
        super(headerParser, listener);
        this.streamId = streamId;
    }

    private void reset()
    {
        state = State.INIT;
        length = 0;
    }

    @Override
    protected void emptyBody(ByteBuffer buffer, boolean last)
    {
        onData(BufferUtil.EMPTY_BUFFER, last, false);
    }

    @Override
    public Result parse(ByteBuffer buffer, boolean last)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case INIT:
                {
                    length = getBodyLength();
                    state = State.DATA;
                    break;
                }
                case DATA:
                {
                    int size = (int)Math.min(buffer.remaining(), length);
                    int position = buffer.position();
                    ByteBuffer slice = buffer.slice(position, size);
                    buffer.position(position + size);

                    length -= size;
                    if (length == 0)
                    {
                        reset();
                        // Only HEADERS, DATA or PUSH_PROMISE can be received.
                        // If the buffer contains more frames that need
                        // to be parsed, then it's not the last frame.
                        // TODO: the sequence: DATA(last=true)+PUSH_PROMISE
                        //  would break the logic in the line below.
                        boolean lastFrame = last && !buffer.hasRemaining();
                        onData(slice, lastFrame, false);
                        return Result.WHOLE_FRAME;
                    }
                    else
                    {
                        // We got partial data, simulate a smaller frame, and stay in DATA state.
                        onData(slice, false, true);
                        return Result.FRAGMENT_FRAME;
                    }
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return Result.NO_FRAME;
    }

    private void onData(ByteBuffer buffer, boolean last, boolean fragment)
    {
        DataFrame frame = new DataFrame(ReadableBuffer.wrap(buffer), last);
        if (LOG.isDebugEnabled())
            LOG.debug("notifying fragment={} {}#{} left={}", fragment, frame, streamId, length);
        notifyData(frame);
    }

    private void notifyData(DataFrame frame)
    {
        ParserListener listener = getParserListener();
        try
        {
            listener.onData(streamId, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    private enum State
    {
        INIT, DATA
    }
}
