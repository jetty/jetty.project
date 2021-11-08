//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal.parser;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.HTTP3ErrorCode;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeadersBodyParser extends BodyParser
{
    private static final Logger LOG = LoggerFactory.getLogger(HeadersBodyParser.class);

    private final List<ByteBuffer> byteBuffers = new ArrayList<>();
    private final long streamId;
    private final BooleanSupplier isLast;
    private final QpackDecoder decoder;
    private State state = State.INIT;
    private long length;

    public HeadersBodyParser(HeaderParser headerParser, ParserListener listener, QpackDecoder decoder, long streamId, BooleanSupplier isLast)
    {
        super(headerParser, listener);
        this.streamId = streamId;
        this.isLast = isLast;
        this.decoder = decoder;
    }

    private void reset()
    {
        state = State.INIT;
        length = 0;
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case INIT:
                {
                    length = getBodyLength();
                    state = State.HEADERS;
                    break;
                }
                case HEADERS:
                {
                    int remaining = buffer.remaining();
                    if (remaining < length)
                    {
                        // Copy and accumulate the buffer.
                        length -= remaining;
                        ByteBuffer copy = buffer.isDirect() ? ByteBuffer.allocateDirect(remaining) : ByteBuffer.allocate(remaining);
                        copy.put(buffer);
                        copy.flip();
                        byteBuffers.add(copy);
                        return Result.NO_FRAME;
                    }
                    else
                    {
                        int position = buffer.position();
                        int limit = buffer.limit();
                        int newPosition = position + (int)length;
                        buffer.limit(newPosition);
                        ByteBuffer slice = buffer.slice();
                        buffer.limit(limit);
                        buffer.position(newPosition);

                        ByteBuffer encoded;
                        if (byteBuffers.isEmpty())
                        {
                            encoded = slice;
                        }
                        else
                        {
                            byteBuffers.add(slice);
                            int capacity = byteBuffers.stream().mapToInt(ByteBuffer::remaining).sum();
                            encoded = byteBuffers.stream().reduce(ByteBuffer.allocate(capacity), ByteBuffer::put);
                            encoded.flip();
                            byteBuffers.clear();
                        }

                        // If the buffer contains another frame that
                        // needs to be parsed, then it's not the last frame.
                        boolean last = isLast.getAsBoolean() && !buffer.hasRemaining();

                        return decode(encoded, last) ? Result.WHOLE_FRAME : Result.NO_FRAME;
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

    private boolean decode(ByteBuffer encoded, boolean last)
    {
        try
        {
            return decoder.decode(streamId, encoded, (streamId, metaData) -> onHeaders(metaData, last));
        }
        catch (QpackException.StreamException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            notifyStreamFailure(streamId, x.getErrorCode(), x);
        }
        catch (QpackException.SessionException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            notifySessionFailure(x.getErrorCode(), x.getMessage(), x);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            notifySessionFailure(HTTP3ErrorCode.INTERNAL_ERROR.code(), "internal_error", x);
        }
        return false;
    }

    private void onHeaders(MetaData metaData, boolean last)
    {
        HeadersFrame frame = new HeadersFrame(metaData, last);
        reset();
        notifyHeaders(frame);
    }

    protected void notifyHeaders(HeadersFrame frame)
    {
        ParserListener listener = getParserListener();
        try
        {
            listener.onHeaders(streamId, frame);
        }
        catch (Throwable x)
        {
            LOG.info("failure while notifying listener {}", listener, x);
        }
    }

    private enum State
    {
        INIT, HEADERS
    }
}
