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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public abstract class AbstractGzipTest
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractGzipTest.class);
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

    protected GzipCompression gzip;

    protected void startGzip() throws Exception
    {
        startGzip(-1);
    }

    protected void startGzip(int bufferSize) throws Exception
    {
        gzip = new GzipCompression();
        if (bufferSize > 0)
            gzip.setBufferSize(bufferSize);

        ByteBufferPool pool = new ByteBufferPool.Wrapper(new ArrayByteBufferPool())
        {
            @Override
            public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
            {
                poolCounter.incrementAndGet();
                RetainableByteBuffer.Mutable buf = new RetainableByteBuffer.Mutable.Wrapper(super.acquire(size, direct))
                {
                    @Override
                    public void retain()
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("retain() - buf={}", this, new RuntimeException("retain()"));
                        super.retain();
                    }

                    @Override
                    public boolean release()
                    {
                        boolean released = super.release();
                        if (LOG.isDebugEnabled())
                            LOG.debug("release() - released={}, buf={}", released, this, new RuntimeException("release()"));
                        if (released)
                            poolCounter.decrementAndGet();
                        return released;
                    }
                };
                if (LOG.isDebugEnabled())
                    LOG.debug("acquire() - buf={}", buf, new RuntimeException("acquire()"));
                return buf;
            }
        };
        gzip.setByteBufferPool(pool);
        gzip.start();
    }

    @AfterEach
    public void stopGzip()
    {
        LifeCycle.stop(gzip);
        assertThat(poolCounter.get(), is(0));
    }

    /**
     * Generate compressed bytes using JVM Built-In GZIP features.
     *
     * @param data the data to compress
     * @return the compressed bytes
     * @throws IOException if unable to compress input data
     */
    public byte[] compress(String data) throws IOException
    {
        // Generate some compressed bytes using GZIP built-in techniques
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             GZIPOutputStream output = new GZIPOutputStream(bytesOut))
        {
            if (data != null)
                output.write(data.getBytes(UTF_8));
            output.close();
            return bytesOut.toByteArray();
        }
    }

    /**
     * Decompress bytes using JVM Built-In GZIP features.
     *
     * @param compressedBytes the data to decompress
     * @return the decompressed bytes
     * @throws IOException if unable to decompress
     */
    public byte[] decompress(byte[] compressedBytes) throws IOException
    {
        try (
            ByteArrayInputStream input = new ByteArrayInputStream(compressedBytes);
            GZIPInputStream gzipInput = new GZIPInputStream(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            IO.copy(gzipInput, output);
            return output.toByteArray();
        }
    }

    /**
     * Decompress ByteBuffer using JVM Built-In GZIP features.
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
