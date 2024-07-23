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

package org.eclipse.jetty.compression.zstandard;

import java.nio.ByteOrder;
import java.util.Set;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compression for Zstandard.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8478">RFC 8478 - Zstandard Compression and the application/zstd Media Type</a>
 */
public class ZstandardCompression extends Compression
{
    private static final Logger LOG = LoggerFactory.getLogger(ZstandardCompression.class);

    private static final String ENCODING_NAME = "zstd";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);
    private static final int DEFAULT_MIN_ZSTD_SIZE = 48;

    private ByteBufferPool byteBufferPool;
    private int bufferSize = 2048;
    private int minCompressSize = DEFAULT_MIN_ZSTD_SIZE;

    public ZstandardCompression()
    {
        super(ENCODING_NAME);
    }

    public int getMinCompressSize()
    {
        return minCompressSize;
    }

    public void setMinCompressSize(int minCompressSize)
    {
        this.minCompressSize = Math.max(minCompressSize, DEFAULT_MIN_ZSTD_SIZE);
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
    public RetainableByteBuffer acquireByteBuffer()
    {
        RetainableByteBuffer buffer = this.byteBufferPool.acquire(getBufferSize(), false);
        buffer.getByteBuffer().order(getByteOrder());
        return buffer;
    }

    private ByteOrder getByteOrder()
    {
        // https://datatracker.ietf.org/doc/html/rfc8478
        // Zstandard is LITTLE_ENDIAN
        return ByteOrder.LITTLE_ENDIAN;
    }

    @Override
    public String getName()
    {
        return "zstandard";
    }

    @Override
    public Set<String> getFileExtensionNames()
    {
        return Set.of("zstd");
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
    public Compression.Decoder newDecoder()
    {
        return newDecoder(getByteBufferPool());
    }

    @Override
    public Compression.Decoder newDecoder(ByteBufferPool pool)
    {
        return new ZstandardDecoder(this, pool);
    }

    @Override
    public Compression.Encoder newEncoder()
    {
        return new ZstandardEncoder(this);
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
