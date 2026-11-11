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

import java.io.IOException;
import java.util.Objects;

import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.HTTP3ErrorCode;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.util.buffer.BufferAggregator;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeadersBodyParser extends BodyParser
{
    private static final Logger LOG = LoggerFactory.getLogger(HeadersBodyParser.class);

    private final QpackDecoder decoder;
    private final long streamId;
    private BufferAggregator aggregator;
    private State state = State.INIT;
    private long bodyLength;

    public HeadersBodyParser(HeaderParser headerParser, ParserListener listener, QpackDecoder decoder, long streamId)
    {
        super(headerParser, listener);
        this.decoder = Objects.requireNonNull(decoder);
        this.streamId = streamId;
    }

    private void reset()
    {
        state = State.INIT;
        bodyLength = 0;
    }

    @Override
    public Result parse(RetainableByteBuffer buffer, boolean quicLast)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case INIT ->
                {
                    long length = getBodyLength();
                    int maxHeadersSize = decoder.getMaxHeadersSize();
                    if (length > (maxHeadersSize > 0 ? maxHeadersSize : Integer.MAX_VALUE))
                    {
                        sessionFailure(buffer, QpackException.QPACK_DECOMPRESSION_FAILED, "headers_too_large", new IOException("headers too large"));
                        return Result.NO_FRAME;
                    }
                    bodyLength = length;
                    state = State.HEADERS;
                }
                case HEADERS ->
                {
                    long remaining = buffer.remaining();
                    if (remaining < bodyLength)
                    {
                        // This case should be rare, so better copying the bytes
                        // than retaining a whole network buffer and having to
                        // arrange the release also in case of errors.
                        // Do not trust the declared length to allocate memory,
                        // and grow as bytes arrive, but no more than that length.

                        if (aggregator == null)
                        {
                            int capacity = Math.min((int)bodyLength, 512);
                            aggregator = new BufferAggregator(null, false, true, capacity, bodyLength);
                        }
                        bodyLength -= remaining;
                        aggregator.append(buffer);
                        return Result.NO_FRAME;
                    }

                    boolean last;
                    RetainableByteBuffer encoded;
                    if (aggregator == null)
                    {
                        if (remaining > bodyLength)
                        {
                            last = false;
                            encoded = buffer.sliceAndConsume(bodyLength);
                        }
                        else
                        {
                            last = quicLast;
                            buffer.retain();
                            encoded = buffer;
                        }
                    }
                    else
                    {
                        if (remaining > bodyLength)
                        {
                            last = false;
                            try (RetainableByteBuffer slice = buffer.sliceAndConsume(bodyLength))
                            {
                                aggregator.append(slice);
                            }
                        }
                        else
                        {
                            last = quicLast;
                            aggregator.append(buffer);
                        }
                        encoded = aggregator.take();
                        aggregator = null;
                    }

                    try (encoded)
                    {
                        return decode(encoded, last);
                    }
                }
            }
        }
        return Result.NO_FRAME;
    }

    private Result decode(RetainableByteBuffer encoded, boolean last)
    {
        try
        {
            boolean decoded = decoder.decode(streamId, encoded, (_, metaData, wasBlocked) ->
                onHeaders(metaData, last, wasBlocked)
            );
            return decoded ? Result.WHOLE_FRAME : Result.BLOCKED_FRAME;
        }
        catch (QpackException.StreamException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);

            // TODO: handle stream exceptions in other cases (see https://github.com/jetty/jetty.project/issues/7676).
            decoder.streamCancellation(streamId);

            if (x.isRequest())
                onHeaders(MetaData.Failed.newFailedMetaDataRequest(HttpVersion.HTTP_3, x), true, false);
            else if (x.isResponse())
                onHeaders(MetaData.Failed.newFailedMetaDataResponse(HttpVersion.HTTP_3, x), true, false);
            else
                onHeaders(MetaData.Failed.newFailedMetaData(HttpVersion.HTTP_3, x), true, false);
            return Result.WHOLE_FRAME;
        }
        catch (QpackException.SessionException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            sessionFailure(encoded, x.getErrorCode(), x.getMessage(), x);
            return Result.NO_FRAME;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("decode failure", x);
            sessionFailure(encoded, HTTP3ErrorCode.INTERNAL_ERROR.code(), "internal_error", x);
            return Result.NO_FRAME;
        }
    }

    private void onHeaders(MetaData metaData, boolean last, boolean wasBlocked)
    {
        Throwable failure = MetaData.Failed.getFailure(metaData);
        if (failure == null || failure instanceof QpackException.StreamException)
        {
            HeadersFrame frame = new HeadersFrame(metaData, last);
            reset();
            notifyHeaders(frame, wasBlocked);
        }
        else
        {
            notifySessionFailure(HTTP3ErrorCode.HTTP_MESSAGE_ERROR.code(), "decode_failure", failure);
        }
    }

    protected void notifyHeaders(HeadersFrame frame, boolean wasBlocked)
    {
        ParserListener listener = getParserListener();
        try
        {
            listener.onHeaders(streamId, frame, wasBlocked);
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
