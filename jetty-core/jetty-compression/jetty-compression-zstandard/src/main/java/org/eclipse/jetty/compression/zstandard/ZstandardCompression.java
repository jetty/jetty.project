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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

import com.github.luben.zstd.ZstdInputStreamNoFinalizer;
import com.github.luben.zstd.ZstdOutputStreamNoFinalizer;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.DecoderConfig;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.EncoderConfig;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Compression for Zstandard.
 *
 * <p>
 * Note about {@link ByteBufferPool}: the {@code zstd-jni} project requires {@link java.nio.ByteBuffer}
 * implementations that are array backed with a zero arrayOffset.
 * </p>
 *
 * @see <a href="https://datatracker.ietf.org/doc/html/rfc8478">RFC 8478 - Zstandard Compression and the application/zstd Media Type</a>
 * @see <a href="https://github.com/luben/zstd-jni">Uses zstd-jni</a>
 */
public class ZstandardCompression extends Compression
{
    private static final Logger LOG = LoggerFactory.getLogger(ZstandardCompression.class);

    private static final String ENCODING_NAME = "zstd";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);
    private static final int DEFAULT_MIN_ZSTD_SIZE = 48;
    private static final List<String> EXTENSIONS = List.of("zst");
    private int minCompressSize = DEFAULT_MIN_ZSTD_SIZE;
    private ZstandardEncoderConfig defaultEncoderConfig = new ZstandardEncoderConfig();
    private ZstandardDecoderConfig defaultDecoderConfig = new ZstandardDecoderConfig();

    public ZstandardCompression()
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

        // Per zstd-jni, these MUST be direct ByteBuffer implementations.
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(length, true);
        if (!buffer.getByteBuffer().isDirect())
        {
            buffer.release();
            throw new IllegalStateException("ByteBufferPool does not return zstd-jni required direct ByteBuffer");
        }
        // We rely on the ByteBufferPool.release(ByteBuffer) performing a ByteBuffer order reset to default (big-endian).
        // Typically, this is done with a BufferUtil.reset(ByteBuffer) call on.
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
        ZstandardDecoderConfig zstandardDecoderConfig = ZstandardDecoderConfig.class.cast(config);
        this.defaultDecoderConfig = Objects.requireNonNull(zstandardDecoderConfig);
    }

    @Override
    public EncoderConfig getDefaultEncoderConfig()
    {
        return this.defaultEncoderConfig;
    }

    @Override
    public void setDefaultEncoderConfig(EncoderConfig config)
    {
        ZstandardEncoderConfig zstandardEncoderConfig = ZstandardEncoderConfig.class.cast(config);
        this.defaultEncoderConfig = Objects.requireNonNull(zstandardEncoderConfig);
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
        this.minCompressSize = Math.max(minCompressSize, DEFAULT_MIN_ZSTD_SIZE);
    }

    @Override
    public String getName()
    {
        return "zstandard";
    }

    @Override
    public HttpField getXContentEncodingField()
    {
        return X_CONTENT_ENCODING;
    }

    @Override
    public InputStream newDecoderInputStream(InputStream in, DecoderConfig config) throws IOException
    {
        ZstandardDecoderConfig zstandardDecoderConfig = ZstandardDecoderConfig.class.cast(config);
        ZstdInputStreamNoFinalizer inputStream = new ZstdInputStreamNoFinalizer(in);
        config.setBufferSize(zstandardDecoderConfig.getBufferSize());
        return inputStream;
    }

    @Override
    public DecoderSource newDecoderSource(Content.Source source, DecoderConfig config)
    {
        ZstandardDecoderConfig zstandardDecoderConfig = ZstandardDecoderConfig.class.cast(config);
        return new ZstandardDecoderSource(this, source, zstandardDecoderConfig);
    }

    @Override
    public OutputStream newEncoderOutputStream(OutputStream out, EncoderConfig config) throws IOException
    {
        ZstandardEncoderConfig zstandardEncoderConfig = ZstandardEncoderConfig.class.cast(config);
        ZstdOutputStreamNoFinalizer outputStream = new ZstdOutputStreamNoFinalizer(out, zstandardEncoderConfig.getCompressionLevel());
        if (zstandardEncoderConfig.getStrategy() >= 0)
            outputStream.setStrategy(zstandardEncoderConfig.getStrategy());
        return outputStream;
    }

    @Override
    public EncoderSink newEncoderSink(Content.Sink sink, EncoderConfig config)
    {
        ZstandardEncoderConfig zstandardEncoderConfig = ZstandardEncoderConfig.class.cast(config);
        return new ZstandardEncoderSink(this, sink, zstandardEncoderConfig);
    }

    private ByteOrder getByteOrder()
    {
        // https://datatracker.ietf.org/doc/html/rfc8478
        // Zstandard is LITTLE_ENDIAN
        return ByteOrder.LITTLE_ENDIAN;
    }
}
