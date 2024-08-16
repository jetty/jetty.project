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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.util.List;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.Content;
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
    public static final List<String> EXTENSIONS = List.of("br");

    static
    {
        Brotli4jLoader.ensureAvailability();
    }

    private static final CompressedContentFormat BR = new CompressedContentFormat("br", ".br");
    private static final String ENCODING_NAME = "br";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);
    private static final int DEFAULT_MIN_BROTLI_SIZE = 48;

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
    public List<String> getFileExtensionNames()
    {
        return EXTENSIONS;
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
        return acquireByteBuffer(getBufferSize());
    }

    @Override
    public RetainableByteBuffer acquireByteBuffer(int length)
    {
        // Zero-capacity buffers aren't released, they MUST NOT come from the pool.
        if (length == 0)
            return RetainableByteBuffer.EMPTY;

        // Can Brotli4J use direct byte buffers?
        RetainableByteBuffer buffer = getByteBufferPool().acquire(length, false);
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
        return new BrotliDecoder(this);
    }

    @Override
    public OutputStream newDecoderOutputStream(OutputStream out) throws IOException
    {
        return new BrotliOutputStream(out);
    }

    @Override
    public DecoderSource newDecoderSource(Content.Source source)
    {
        return new BrotliDecoderSource(this, source);
    }

    @Override
    public Encoder newEncoder()
    {
        return new BrotliEncoder(this);
    }

    @Override
    public InputStream newEncoderInputStream(InputStream in) throws IOException
    {
        return new BrotliInputStream(in);
    }

    @Override
    public EncoderSink newEncoderSink(Content.Sink sink)
    {
        return new BrotliEncoderSink(this, sink);
    }
}
