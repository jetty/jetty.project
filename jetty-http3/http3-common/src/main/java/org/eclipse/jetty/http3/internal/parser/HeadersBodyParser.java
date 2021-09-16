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
import org.eclipse.jetty.http3.ErrorCode;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.util.BufferUtil;
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
    public boolean parse(ByteBuffer buffer)
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
                        ByteBuffer copy = BufferUtil.copy(buffer);
                        byteBuffers.add(copy);
                        return false;
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
                            byteBuffers.clear();
                        }

                        return decode(encoded);
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

    private boolean decode(ByteBuffer encoded)
    {
        try
        {
            return decoder.decode(streamId, encoded, (streamId, metaData) -> onHeaders(metaData));
        }
        catch (QpackException.StreamException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            notifyStreamFailure(streamId, x.getErrorCode(), x.getMessage());
        }
        catch (QpackException.SessionException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            notifySessionFailure(x.getErrorCode(), x.getMessage());
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            notifySessionFailure(ErrorCode.INTERNAL_ERROR.code(), "internal_error");
        }
        return false;
    }

    private void onHeaders(MetaData metaData)
    {
        HeadersFrame frame = new HeadersFrame(metaData, isLast.getAsBoolean());
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
