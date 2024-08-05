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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public abstract class AbstractZstdTest
{
    // Signed Integer Max
    protected static final long INT_MAX = Integer.MAX_VALUE;
    // Unsigned Integer Max == 2^32
    protected static final long UINT_MAX = 0xFFFFFFFFL;

    protected static final ByteBuffer EMPTY_DIRECT_BUFFER = ByteBuffer.allocateDirect(0);

    private final AtomicInteger poolCounter = new AtomicInteger();

    @AfterEach
    public void after()
    {
        assertThat(poolCounter.get(), is(0));
    }

    protected ZstandardCompression zstd;

    protected void startZstd() throws Exception
    {
        startZstd(-1);
    }

    protected void startZstd(int bufferSize) throws Exception
    {
        zstd = new ZstandardCompression();
        if (bufferSize > 0)
            zstd.setBufferSize(bufferSize);

        ByteBufferPool pool = new ByteBufferPool.Wrapper(new ArrayByteBufferPool())
        {
            @Override
            public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
            {
                poolCounter.incrementAndGet();
                return new RetainableByteBuffer.Mutable.Wrapper(super.acquire(size, direct))
                {
                    @Override
                    public boolean release()
                    {
                        boolean released = super.release();
                        if (released)
                            poolCounter.decrementAndGet();
                        return released;
                    }
                };
            }
        };
        zstd.setByteBufferPool(pool);
        zstd.start();
    }

    @AfterEach
    public void stopBrotli()
    {
        LifeCycle.stop(zstd);
        assertThat("ByteBufferPool counter", poolCounter.get(), is(0));
    }

    /**
     * Create a Direct ByteBuffer from a byte array.
     *
     * <p>
     *     This is a replacement of {@link ByteBuffer#wrap(byte[])} but
     *     for producing Direct {@link ByteBuffer} implementations that
     *     {@code zstd-jni} require.
     * </p>
     *
     * @param arr the byte array to populate ByteBuffer.
     * @return the Direct ByteBuffer representing the byte array.
     */
    public ByteBuffer asDirect(byte[] arr)
    {
        ByteBuffer buf = ByteBuffer.allocateDirect(arr.length);
        buf.put(arr);
        buf.flip();
        return buf;
    }

    /**
     * Compress data using zstd-jni {@code ZstdOutputStream}.
     *
     * @param data the data to compress
     * @return the compressed bytes
     * @throws IOException if unable to compress input data
     */
    public byte[] compress(String data) throws IOException
    {
        // return Zstd.compress(data.getBytes(UTF_8));
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             ZstdOutputStream output = new ZstdOutputStream(bytesOut, zstd.getCompressionLevel()))
        {
            if (data != null)
                output.write(data.getBytes(UTF_8));
            output.close();
            return bytesOut.toByteArray();
        }
    }

    /**
     * Decompress bytes using zstd-jni {@code ZstdInputStream}.
     *
     * @param compressedBytes the data to decompress
     * @return the decompressed bytes
     * @throws IOException if unable to decompress
     */
    public byte[] decompress(byte[] compressedBytes) throws IOException
    {
        try (
            ByteArrayInputStream input = new ByteArrayInputStream(compressedBytes);
            ZstdInputStream brotliInput = new ZstdInputStream(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            IO.copy(brotliInput, output);
            return output.toByteArray();
        }
    }

    /**
     * Decompress ByteBuffer using zstd-jni {@code ZstdInputStream}.
     *
     * @param compressedBytes the data to decompress
     * @return the decompressed bytes
     * @throws IOException if unable to decompress
     */
    public byte[] decompress(ByteBuffer compressedBytes) throws IOException
    {
        return decompress(BufferUtil.toArray(compressedBytes));
    }

}
