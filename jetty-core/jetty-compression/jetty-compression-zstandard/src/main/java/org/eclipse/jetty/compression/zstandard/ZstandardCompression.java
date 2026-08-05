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
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

import com.github.luben.zstd.BufferPool;
import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.github.luben.zstd.util.Native;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.DecoderConfig;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.EncoderConfig;
import org.eclipse.jetty.compression.EncoderSink;
import org.eclipse.jetty.compression.zstandard.internal.ZstandardDecoderSource;
import org.eclipse.jetty.compression.zstandard.internal.ZstandardEncoderSink;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.WritableBuffer;

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
    private static final String ENCODING_NAME = "zstd";
    private static final HttpField X_CONTENT_ENCODING = new PreEncodedHttpField("X-Content-Encoding", ENCODING_NAME);
    private static final HttpField CONTENT_ENCODING = new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, ENCODING_NAME);
    private static final int DEFAULT_MIN_ZSTD_SIZE = 48;
    private static final List<String> EXTENSIONS = List.of("zst");

    private final Cleaner cleaner = Cleaner.create();
    private ZstandardEncoderConfig defaultEncoderConfig = new ZstandardEncoderConfig();
    private ZstandardDecoderConfig defaultDecoderConfig = new ZstandardDecoderConfig();

    public ZstandardCompression()
    {
        super(ENCODING_NAME);
        // Zstandard's Native uses a static class block to link
        // the native library, so it is thread-safe by definition.
        Native.load();
        setMinCompressSize(DEFAULT_MIN_ZSTD_SIZE);
    }

    @Override
    public WritableBuffer acquireBuffer(int length)
    {
        // Per zstd-jni, these MUST be direct ByteBuffer implementations.
        WritableBuffer buffer = getBufferPool().acquire(length, true);
        // We rely on the ByteBufferPool.release(ByteBuffer) performing a ByteBuffer order reset to default (big-endian).
        // Typically, this is done with a BufferUtil.reset(ByteBuffer) call on.
        // https://datatracker.ietf.org/doc/html/rfc8478
        // Zstandard is LITTLE_ENDIAN
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
    public void setDefaultDecoderConfig(DecoderConfig config)
    {
        ZstandardDecoderConfig zstandardDecoderConfig = (ZstandardDecoderConfig)config;
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
        ZstandardEncoderConfig zstandardEncoderConfig = (ZstandardEncoderConfig)config;
        this.defaultEncoderConfig = Objects.requireNonNull(zstandardEncoderConfig);
    }

    @Override
    public List<String> getFileExtensionNames()
    {
        return EXTENSIONS;
    }

    @Override
    public void setMinCompressSize(int minCompressSize)
    {
        super.setMinCompressSize(Math.max(minCompressSize, DEFAULT_MIN_ZSTD_SIZE));
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
        return new ZstdInputStream(in, new BufferPoolAdapter(getBufferPool(), false));
    }

    @Override
    public DecoderSource newDecoderSource(Content.Source source, DecoderConfig config)
    {
        ZstandardDecoderConfig zstandardDecoderConfig = (ZstandardDecoderConfig)config;
        return new ZstandardDecoderSource(source, this, zstandardDecoderConfig);
    }

    @Override
    public OutputStream newEncoderOutputStream(OutputStream out, EncoderConfig config) throws IOException
    {
        ZstandardEncoderConfig zstandardEncoderConfig = (ZstandardEncoderConfig)config;
        ZstdOutputStream outputStream = new ZstdOutputStream(out, new BufferPoolAdapter(getBufferPool(), false), zstandardEncoderConfig.getCompressionLevel());
        if (zstandardEncoderConfig.getStrategy() >= 0)
            outputStream.setStrategy(zstandardEncoderConfig.getStrategy());
        return outputStream;
    }

    @Override
    public EncoderSink newEncoderSink(Content.Sink sink, EncoderConfig config)
    {
        ZstandardEncoderConfig zstandardEncoderConfig = (ZstandardEncoderConfig)config;
        return new ZstandardEncoderSink(this, sink, zstandardEncoderConfig);
    }

    public Cleaner getCleaner()
    {
        return cleaner;
    }

    private static class BufferPoolAdapter implements BufferPool
    {
        private final IdentityHashMap<ByteBuffer, WritableBuffer> buffers = new IdentityHashMap<>();
        private final WritableBufferPool bufferPool;
        private final boolean direct;

        public BufferPoolAdapter(WritableBufferPool bufferPool, boolean direct)
        {
            this.bufferPool = bufferPool;
            this.direct = direct;
        }

        @Override
        public ByteBuffer get(int capacity)
        {
            WritableBuffer wb = bufferPool.acquire(capacity, direct);
            // Hack to extract the ByteBuffer from the WritableBuffer. TODO: how to clean this up?
            ByteBuffer[] ba = new ByteBuffer[1];
            try
            {
                wb.readFrom(output ->
                {
                    ba[0] = output;
                    return false;
                });
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
            ByteBuffer byteBuffer = ba[0];
            buffers.put(byteBuffer, wb);
            return byteBuffer;
        }

        @Override
        public void release(ByteBuffer buffer)
        {
            WritableBuffer removed = buffers.remove(buffer);
            if (removed != null)
                removed.release();
        }
    }
}
