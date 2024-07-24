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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
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

public abstract class AbstractBrotliTest
{
    // Signed Integer Max
    protected static final long INT_MAX = Integer.MAX_VALUE;
    // Unsigned Integer Max == 2^32
    protected static final long UINT_MAX = 0xFFFFFFFFL;

    private final AtomicInteger poolCounter = new AtomicInteger();

    @AfterEach
    public void after()
    {
        assertThat(poolCounter.get(), is(0));
    }

    protected BrotliCompression brotli;

    protected void startBrotli() throws Exception
    {
        startBrotli(-1);
    }

    protected void startBrotli(int bufferSize) throws Exception
    {
        brotli = new BrotliCompression();
        if (bufferSize > 0)
            brotli.setBufferSize(bufferSize);

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
        brotli.setByteBufferPool(pool);
        brotli.start();
    }

    @AfterEach
    public void stopBrotli()
    {
        LifeCycle.stop(brotli);
        assertThat("ByteBufferPool counter", poolCounter.get(), is(0));
    }

    /**
     * Compress data using Brotli4j {@code BrotliOutputStream}.
     *
     * @param data the data to compress
     * @return the compressed bytes
     * @throws IOException if unable to compress input data
     */
    public byte[] compress(String data) throws IOException
    {
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(bytesOut, brotli.getEncoderParams()))
        {
            if (data != null)
                output.write(data.getBytes(UTF_8));
            output.close();
            return bytesOut.toByteArray();
        }
    }

    /**
     * Decompress bytes using Brotli4j {@code BrotliInputStream}.
     *
     * @param compressedBytes the data to decompress
     * @return the decompressed bytes
     * @throws IOException if unable to decompress
     */
    public byte[] decompress(byte[] compressedBytes) throws IOException
    {
        try (
            ByteArrayInputStream input = new ByteArrayInputStream(compressedBytes);
            BrotliInputStream brotliInput = new BrotliInputStream(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            IO.copy(brotliInput, output);
            return output.toByteArray();
        }
    }

    /**
     * Decompress ByteBuffer using Brotli4j {@code BrotliInputStream}.
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
