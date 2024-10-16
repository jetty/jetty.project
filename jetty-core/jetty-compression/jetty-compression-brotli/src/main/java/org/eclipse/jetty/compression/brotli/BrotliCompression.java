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
import java.util.Objects;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.DecoderConfig;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.EncoderConfig;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.HttpField;
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
    private static final List<String> EXTENSIONS = List.of("br");
    private static final Logger LOG = LoggerFactory.getLogger(BrotliCompression.class);
    private static final CompressedContentFormat BR = new CompressedContentFormat("br", ".br");
    private static final String ENCODING_NAME = "br";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);
    private static final int DEFAULT_MIN_BROTLI_SIZE = 48;

    static
    {
        Brotli4jLoader.ensureAvailability();
    }

    private BrotliEncoderConfig defaultEncoderConfig = new BrotliEncoderConfig();
    private BrotliDecoderConfig defaultDecoderConfig = new BrotliDecoderConfig();

    private int minCompressSize = DEFAULT_MIN_BROTLI_SIZE;

    public BrotliCompression()
    {
        super(ENCODING_NAME);
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

    @Override
    public HttpField getContentEncodingField()
    {
        return CONTENT_ENCODING;
    }

    @Override
    public DecoderConfig getDefaultDecoderConfig()
    {
        return this.defaultDecoderConfig;
    }

    @Override
    public void setDefaultDecoderConfig(DecoderConfig config)
    {
        BrotliDecoderConfig brotliDecoderConfig = BrotliDecoderConfig.class.cast(config);
        this.defaultDecoderConfig = brotliDecoderConfig;
    }

    @Override
    public EncoderConfig getDefaultEncoderConfig()
    {
        return this.defaultEncoderConfig;
    }

    @Override
    public void setDefaultEncoderConfig(EncoderConfig config)
    {
        BrotliEncoderConfig brotliEncoderConfig = BrotliEncoderConfig.class.cast(config);
        this.defaultEncoderConfig = Objects.requireNonNull(brotliEncoderConfig);
    }

    @Override
    public List<String> getFileExtensionNames()
    {
        return EXTENSIONS;
    }

    @Override
    public int getMinCompressSize()
    {
        return minCompressSize;
    }

    @Override
    public void setMinCompressSize(int minCompressSize)
    {
        this.minCompressSize = Math.max(minCompressSize, DEFAULT_MIN_BROTLI_SIZE);
    }

    @Override
    public String getName()
    {
        return "brotli";
    }

    @Override
    public HttpField getXContentEncodingField()
    {
        return X_CONTENT_ENCODING;
    }

    @Override
    public InputStream newDecoderInputStream(InputStream in, DecoderConfig config) throws IOException
    {
        BrotliDecoderConfig brotliDecoderConfig = BrotliDecoderConfig.class.cast(config);
        return new BrotliInputStream(in, config.getBufferSize());
    }

    @Override
    public DecoderSource newDecoderSource(Content.Source source, DecoderConfig config)
    {
        BrotliDecoderConfig brotliDecoderConfig = BrotliDecoderConfig.class.cast(config);
        return new BrotliDecoderSource(this, source, brotliDecoderConfig);
    }

    @Override
    public OutputStream newEncoderOutputStream(OutputStream out, EncoderConfig config) throws IOException
    {
        BrotliEncoderConfig brotliEncoderConfig = BrotliEncoderConfig.class.cast(config);
        return new BrotliOutputStream(out, brotliEncoderConfig.asEncoderParams());
    }

    @Override
    public EncoderSink newEncoderSink(Content.Sink sink, EncoderConfig config)
    {
        BrotliEncoderConfig brotliEncoderConfig = BrotliEncoderConfig.class.cast(config);
        return new BrotliEncoderSink(this, sink, brotliEncoderConfig);
    }

    private ByteOrder getByteOrder()
    {
        // Per https://datatracker.ietf.org/doc/html/rfc7932#section-1.5
        // Brotli is LITTLE_ENDIAN
        return ByteOrder.LITTLE_ENDIAN;
    }
}
