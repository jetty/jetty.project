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
import org.eclipse.jetty.compression.brotli.internal.BrotliDecoderSource;
import org.eclipse.jetty.compression.brotli.internal.BrotliEncoderSink;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.buffer.WritableBuffer;

/**
 * Brotli Compression.
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc7932">RFC7932: Brotli Compressed Data Format</a>
 */
public class BrotliCompression extends Compression
{
    private static final List<String> EXTENSIONS = List.of("br");
    private static final String ENCODING_NAME = "br";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);
    private static final int DEFAULT_MIN_BROTLI_SIZE = 48;

    private BrotliEncoderConfig defaultEncoderConfig = new BrotliEncoderConfig();
    private BrotliDecoderConfig defaultDecoderConfig = new BrotliDecoderConfig();

    public BrotliCompression()
    {
        super(ENCODING_NAME);
        // Brotli4jLoader uses a static class block to link the
        // native library, so it is thread-safe by definition.
        Brotli4jLoader.ensureAvailability();
        setMinCompressSize(DEFAULT_MIN_BROTLI_SIZE);
    }

    @Override
    public WritableBuffer acquireBuffer(int length)
    {
        WritableBuffer buffer = getBufferPool().acquire(length, true);
        // Per https://datatracker.ietf.org/doc/html/rfc7932#section-1.5
        // Brotli is LITTLE_ENDIAN
        buffer.byteOrder(true);
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
    public InputStream newDecoderInputStream(InputStream in, DecoderConfig config) throws IOException
    {
        BrotliDecoderConfig brotliDecoderConfig = (BrotliDecoderConfig)config;
        return new BrotliInputStream(in, brotliDecoderConfig.getBufferSize());
    }

    @Override
    public EncoderConfig getDefaultEncoderConfig()
    {
        return this.defaultEncoderConfig;
    }

    @Override
    public DecoderSource newDecoderSource(Content.Source source, DecoderConfig config)
    {
        BrotliDecoderConfig brotliDecoderConfig = (BrotliDecoderConfig)config;
        return new BrotliDecoderSource(source, this, brotliDecoderConfig);
    }

    @Override
    public List<String> getFileExtensionNames()
    {
        return EXTENSIONS;
    }

    @Override
    public void setMinCompressSize(int minCompressSize)
    {
        super.setMinCompressSize(Math.max(minCompressSize, DEFAULT_MIN_BROTLI_SIZE));
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
    public OutputStream newEncoderOutputStream(OutputStream out, EncoderConfig config) throws IOException
    {
        BrotliEncoderConfig brotliEncoderConfig = (BrotliEncoderConfig)config;
        return new BrotliOutputStream(out, brotliEncoderConfig.asEncoderParams());
    }

    @Override
    public EncoderSink newEncoderSink(Content.Sink sink, EncoderConfig config)
    {
        BrotliEncoderConfig brotliEncoderConfig = (BrotliEncoderConfig)config;
        return new BrotliEncoderSink(sink, brotliEncoderConfig);
    }

    @Override
    public void setDefaultDecoderConfig(DecoderConfig config)
    {
        this.defaultDecoderConfig = (BrotliDecoderConfig)config;
    }

    @Override
    public void setDefaultEncoderConfig(EncoderConfig config)
    {
        BrotliEncoderConfig brotliEncoderConfig = (BrotliEncoderConfig)config;
        this.defaultEncoderConfig = Objects.requireNonNull(brotliEncoderConfig);
    }
}
