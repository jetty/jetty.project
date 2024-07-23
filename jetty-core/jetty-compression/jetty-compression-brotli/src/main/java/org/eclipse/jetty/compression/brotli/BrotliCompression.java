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

package org.eclipse.jetty.compression.brotli;

import java.nio.ByteOrder;
import java.util.Set;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brotli Compression.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7932">RFC7932: Brotli Compressed Data Format</a>
 */
public class BrotliCompression extends Compression
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliCompression.class);

    static
    {
        Brotli4jLoader.ensureAvailability();
    }

    private static final CompressedContentFormat BR = new CompressedContentFormat("br", ".br");
    private static final String ENCODING_NAME = "br";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);
    private static final int DEFAULT_MIN_BROTLI_SIZE = 48;

    private ByteBufferPool byteBufferPool;
    private int bufferSize = 2048;
    private int minCompressSize = DEFAULT_MIN_BROTLI_SIZE;

    public BrotliCompression()
    {
        super(ENCODING_NAME);
    }

    public com.aayushatharva.brotli4j.encoder.Encoder.Parameters getEncoderParams()
    {
        com.aayushatharva.brotli4j.encoder.Encoder.Parameters params = new com.aayushatharva.brotli4j.encoder.Encoder.Parameters();
        params.setQuality(5); // TODO: make configurable
        params.setMode(com.aayushatharva.brotli4j.encoder.Encoder.Mode.GENERIC);
        return params;
    }

    public int getMinCompressSize()
    {
        return minCompressSize;
    }

    public void setMinCompressSize(int minCompressSize)
    {
        this.minCompressSize = Math.max(minCompressSize, DEFAULT_MIN_BROTLI_SIZE);
    }

    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    @Override
    public boolean acceptsCompression(HttpFields headers, long contentLength)
    {
        if (contentLength >= 0 && contentLength < minCompressSize)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded minCompressSize {}", this, headers);
            return false;
        }

        // check the accept encoding header
        if (!headers.contains(HttpHeader.ACCEPT_ENCODING, getEncodingName()))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} excluded not {} acceptable {}", this, getEncodingName(), headers);
            return false;
        }

        return true;
    }

    @Override
    public String getName()
    {
        return "brotli";
    }

    @Override
    public Set<String> getFileExtensionNames()
    {
        return Set.of("br");
    }

    @Override
    public HttpField getXContentEncodingField()
    {
        return X_CONTENT_ENCODING;
    }

    @Override
    public HttpField getContentEncodingField()
    {
        return CONTENT_ENCODING;
    }

    @Override
    public RetainableByteBuffer acquireByteBuffer()
    {
        RetainableByteBuffer buffer = this.byteBufferPool.acquire(getBufferSize(), false);
        buffer.getByteBuffer().order(getByteOrder());
        return buffer;
    }

    private ByteOrder getByteOrder()
    {
        // Per https://datatracker.ietf.org/doc/html/rfc7932#section-1.5
        // Brotli is LITTLE_ENDIAN
        return ByteOrder.LITTLE_ENDIAN;
    }

    @Override
    public Decoder newDecoder()
    {
        return newDecoder(getByteBufferPool());
    }

    @Override
    public Decoder newDecoder(ByteBufferPool pool)
    {
        return new BrotliDecoder(this, pool);
    }

    @Override
    public Encoder newEncoder()
    {
        return new BrotliEncoder(this);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (byteBufferPool == null)
        {
            byteBufferPool = ByteBufferPool.NON_POOLING;
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }
}
