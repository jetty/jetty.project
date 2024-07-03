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

package org.eclipse.jetty.compression.gzip;

import java.util.Set;
import java.util.zip.Deflater;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.compression.DeflaterPool;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GzipCompression extends Compression
{
    private static final Logger LOG = LoggerFactory.getLogger(GzipCompression.class);

    public static final int DEFAULT_MIN_GZIP_SIZE = 32;
    public static final int BREAK_EVEN_GZIP_SIZE = 23;

    private static final String ENCODING_NAME = "gzip";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);

    private ByteBufferPool byteBufferPool;
    private int bufferSize = 2048;
    private int minCompressSize = DEFAULT_MIN_GZIP_SIZE;
    private DeflaterPool deflaterPool;
    private InflaterPool inflaterPool;
    private boolean syncFlush;

    public GzipCompression()
    {
        super(ENCODING_NAME);
    }

    public int getBufferSize()
    {
        return bufferSize;
    }

    /**
     * Is the {@link Deflater} running {@link Deflater#SYNC_FLUSH} or not.
     *
     * @return True if {@link Deflater#SYNC_FLUSH} is used, else {@link Deflater#NO_FLUSH}
     * @see #setSyncFlush(boolean)
     */
    public boolean isSyncFlush()
    {
        return syncFlush;
    }

    /**
     * Set the {@link Deflater} flush mode to use.  {@link Deflater#SYNC_FLUSH}
     * should be used if the application wishes to stream the data, but this may
     * hurt compression performance.
     *
     * @param syncFlush True if {@link Deflater#SYNC_FLUSH} is used, else {@link Deflater#NO_FLUSH}
     * @see #isSyncFlush()
     */
    public void setSyncFlush(boolean syncFlush)
    {
        syncFlush = syncFlush;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public int getMinCompressSize()
    {
        return minCompressSize;
    }

    public void setMinCompressSize(int minCompressSize)
    {
        this.minCompressSize = Math.max(minCompressSize, DEFAULT_MIN_GZIP_SIZE);
    }

    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
    }

    public void setBufferSize(int bufferSize)
    {
        this.bufferSize = bufferSize;
    }

    @Override
    public String getName()
    {
        return "gzip";
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
    public Set<String> getFileExtensionNames()
    {
        return Set.of("gz", "gzip");
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

    protected InflaterPool getInflaterPool()
    {
        return this.inflaterPool;
    }

    protected DeflaterPool getDeflaterPool()
    {
        return this.deflaterPool;
    }

    @Override
    public Decoder newDecoder()
    {
        return newDecoder(getByteBufferPool());
    }

    @Override
    public Decoder newDecoder(ByteBufferPool pool)
    {
        return new GzipDecoder(this, pool);
    }

    @Override
    public Encoder newEncoder(int outputBufferSize)
    {
        return newEncoder(getByteBufferPool(), getBufferSize());
    }

    @Override
    public Encoder newEncoder(ByteBufferPool pool, int outputBufferSize)
    {
        return new GzipEncoder(this, pool, outputBufferSize);
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();

        if (byteBufferPool == null)
        {
            byteBufferPool = ByteBufferPool.NON_POOLING;
        }

        if (deflaterPool == null)
        {
            deflaterPool = DeflaterPool.ensurePool(getContainer());
            addBean(deflaterPool);
        }

        if (inflaterPool == null)
        {
            inflaterPool = InflaterPool.ensurePool(getContainer());
            addBean(inflaterPool);
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        removeBean(inflaterPool);
        inflaterPool = null;

        removeBean(deflaterPool);
        deflaterPool = null;
    }
}
