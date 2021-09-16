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

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.ErrorCode;
import org.eclipse.jetty.http3.frames.Frame;
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
    private final QpackDecoder decoder;
    private State state = State.INIT;
    private long length;
    private Frame frame;

    public HeadersBodyParser(long streamId, HeaderParser headerParser, QpackDecoder decoder)
    {
        super(streamId, headerParser);
        this.decoder = decoder;
    }

    private void reset()
    {
        state = State.INIT;
        length = 0;
        frame = null;
    }

    @Override
    public Frame parse(ByteBuffer buffer) throws ParseException
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
                        return null;
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
        return null;
    }

    private Frame decode(ByteBuffer encoded) throws ParseException
    {
        try
        {
            // TODO: do a proper reset when the lambda is notified asynchronously.
            if (decoder.decode(getStreamId(), encoded, (streamId, metaData) -> this.frame = onHeaders(metaData)))
            {
                Frame frame = this.frame;
                reset();
                return frame;
            }
            return null;
        }
        catch (QpackException.StreamException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            throw new ParseException(x.getErrorCode(), x.getMessage());
        }
        catch (QpackException.SessionException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            throw new ParseException(x.getErrorCode(), x.getMessage(), true);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            throw new ParseException(ErrorCode.INTERNAL_ERROR.code(), "internal_error", true, x);
        }
    }

    private Frame onHeaders(MetaData metaData)
    {
        return new HeadersFrame(metaData, false);
    }

    private enum State
    {
        INIT, HEADERS
    }
}
