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

package org.eclipse.jetty.client;

import java.util.ListIterator;

import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;

/**
 * {@link ContentDecoder} for the "gzip" encoding.
 */
public class GZIPContentDecoder extends org.eclipse.jetty.http.GZIPContentDecoder implements ContentDecoder
{
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    private long decodedLength;

    public GZIPContentDecoder()
    {
        this(DEFAULT_BUFFER_SIZE);
    }

    public GZIPContentDecoder(int bufferSize)
    {
        this(null, bufferSize);
    }

    public GZIPContentDecoder(ByteBufferPool byteBufferPool, int bufferSize)
    {
        super(byteBufferPool, bufferSize);
    }

    @Override
    public void beforeDecoding(Response response)
    {
        HttpResponse httpResponse = (HttpResponse)response;
        httpResponse.headers(headers ->
        {
            boolean seenContentEncoding = false;
            for (ListIterator<HttpField> iterator = headers.listIterator(headers.size()); iterator.hasPrevious();)
            {
                HttpField field = iterator.previous();
                HttpHeader header = field.getHeader();
                if (header == HttpHeader.CONTENT_LENGTH)
                {
                    // Content-Length is not valid anymore while we are decoding.
                    iterator.remove();
                }
                else if (header == HttpHeader.CONTENT_ENCODING && !seenContentEncoding)
                {
                    // Last Content-Encoding should be removed/modified as the content will be decoded.
                    seenContentEncoding = true;
                    String value = field.getValue();
                    int comma = value.lastIndexOf(",");
                    if (comma < 0)
                        iterator.remove();
                    else
                        iterator.set(new HttpField(HttpHeader.CONTENT_ENCODING, value.substring(0, comma)));
                }
            }
        });
    }

    @Override
    protected boolean decodedChunk(RetainableByteBuffer chunk)
    {
        decodedLength += chunk.remaining();
        super.decodedChunk(chunk);
        return true;
    }

    @Override
    public void afterDecoding(Response response)
    {
        HttpResponse httpResponse = (HttpResponse)response;
        httpResponse.headers(headers ->
        {
            headers.remove(HttpHeader.TRANSFER_ENCODING);
            headers.put(HttpHeader.CONTENT_LENGTH, decodedLength);
        });
    }

    /**
     * Specialized {@link ContentDecoder.Factory} for the "gzip" encoding.
     */
    public static class Factory extends ContentDecoder.Factory
    {
        private final ByteBufferPool byteBufferPool;
        private final int bufferSize;

        public Factory()
        {
            this(DEFAULT_BUFFER_SIZE);
        }

        public Factory(int bufferSize)
        {
            this(null, bufferSize);
        }

        public Factory(ByteBufferPool byteBufferPool)
        {
            this(byteBufferPool, DEFAULT_BUFFER_SIZE);
        }

        public Factory(ByteBufferPool byteBufferPool, int bufferSize)
        {
            super("gzip");
            this.byteBufferPool = byteBufferPool;
            this.bufferSize = bufferSize;
        }

        @Override
        public ContentDecoder newContentDecoder()
        {
            return new GZIPContentDecoder(byteBufferPool, bufferSize);
        }
    }
}
