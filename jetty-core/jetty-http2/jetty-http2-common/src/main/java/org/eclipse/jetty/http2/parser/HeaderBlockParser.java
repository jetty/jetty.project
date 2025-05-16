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

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeaderBlockParser
{
    private static final SessionFailureMetaData SESSION_FAILURE = new SessionFailureMetaData();
    private static final Logger LOG = LoggerFactory.getLogger(HeaderBlockParser.class);

    private final HeaderParser headerParser;
    private final ByteBufferPool bufferPool;
    private final HpackDecoder hpackDecoder;
    private final BodyParser notifier;
    private RetainableByteBuffer blockBuffer;

    public HeaderBlockParser(HeaderParser headerParser, ByteBufferPool bufferPool, HpackDecoder hpackDecoder, BodyParser notifier)
    {
        this.headerParser = headerParser;
        this.bufferPool = bufferPool;
        this.hpackDecoder = hpackDecoder;
        this.notifier = notifier;
    }

    public int getMaxHeaderListSize()
    {
        return hpackDecoder.getMaxHeaderListSize();
    }

    /**
     * Parses @{code blockLength} HPACK bytes from the given {@code buffer}.
     *
     * @param buffer the buffer to parse
     * @param blockLength the length of the HPACK block
     * @return null, if the buffer contains less than {@code blockLength} bytes;
     * an instance of {@link StreamFailureMetaData} if parsing the HPACK block produced a stream failure;
     * an instance of {@link SessionFailureMetaData} if parsing the HPACK block produced a session failure;
     * an instance of {@link MetaData} if the parsing was successful.
     */
    public MetaData parse(ByteBuffer buffer, int blockLength)
    {
        // We must wait for the all the bytes of the header block to arrive.
        // If they are not all available, accumulate them.
        // When all are available, decode them.

        ByteBuffer byteBuffer = blockBuffer == null ? null : blockBuffer.getByteBuffer();
        int accumulated = byteBuffer == null ? 0 : byteBuffer.position();
        int remaining = blockLength - accumulated;

        if (buffer.remaining() < remaining)
        {
            if (blockBuffer == null)
            {
                blockBuffer = bufferPool.acquire(blockLength, buffer.isDirect());
                byteBuffer = blockBuffer.getByteBuffer();
                BufferUtil.flipToFill(byteBuffer);
            }
            byteBuffer.put(buffer);
            return null;
        }
        else
        {
            int limit = buffer.limit();
            buffer.limit(buffer.position() + remaining);
            ByteBuffer toDecode;
            if (byteBuffer != null)
            {
                byteBuffer.put(buffer);
                BufferUtil.flipToFlush(byteBuffer, 0);
                toDecode = byteBuffer;
            }
            else
            {
                toDecode = buffer;
            }

            try
            {
                return hpackDecoder.decode(toDecode);
            }
            catch (HpackException.StreamException x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Stream error, stream={}", headerParser.getStreamId(), x);
                if (x.isRequest())
                    return new StreamFailureMetaDataRequest(x);
                if (x.isResponse())
                    return new StreamFailureMetaDataResponse(x);
                return new StreamFailureMetaDataTrailer(x);
            }
            catch (HpackException.CompressionException x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Compression error, buffer={}", BufferUtil.toDetailString(buffer), x);
                notifier.connectionFailure(buffer, ErrorCode.COMPRESSION_ERROR.code, "invalid_hpack_block");
                return SESSION_FAILURE;
            }
            catch (HpackException.SessionException x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Session error, buffer={}", BufferUtil.toDetailString(buffer), x);
                notifier.connectionFailure(buffer, ErrorCode.PROTOCOL_ERROR.code, "invalid_hpack_block");
                return SESSION_FAILURE;
            }
            finally
            {
                buffer.limit(limit);

                if (blockBuffer != null)
                {
                    blockBuffer.release();
                    blockBuffer = null;
                }
            }
        }
    }

    public static class SessionFailureMetaData extends MetaData
    {
        private SessionFailureMetaData()
        {
            super(HttpVersion.HTTP_2, HttpFields.EMPTY);
        }
    }

    public interface StreamFailureMetaData
    {
        Throwable getFailure();
    }

    public static class StreamFailureMetaDataRequest extends MetaData.Request implements StreamFailureMetaData
    {
        private final Throwable failure;

        private StreamFailureMetaDataRequest(Throwable failure)
        {
            super("GET", HttpURI.build(), HttpVersion.HTTP_2, HttpFields.EMPTY);
            this.failure = failure;
        }

        @Override
        public Throwable getFailure()
        {
            return failure;
        }
    }

    public static class StreamFailureMetaDataResponse extends MetaData.Response implements StreamFailureMetaData
    {
        private final Throwable failure;

        private StreamFailureMetaDataResponse(Throwable failure)
        {
            super(0, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
            this.failure = failure;
        }

        @Override
        public Throwable getFailure()
        {
            return failure;
        }
    }

    public static class StreamFailureMetaDataTrailer extends MetaData implements StreamFailureMetaData
    {
        private final Throwable failure;

        private StreamFailureMetaDataTrailer(Throwable failure)
        {
            super(HttpVersion.HTTP_2, HttpFields.EMPTY);
            this.failure = failure;
        }

        @Override
        public Throwable getFailure()
        {
            return failure;
        }
    }
}
