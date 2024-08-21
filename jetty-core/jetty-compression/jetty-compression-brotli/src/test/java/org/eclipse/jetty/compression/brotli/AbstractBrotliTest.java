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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.List;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

public abstract class AbstractBrotliTest
{
    // Signed Integer Max
    protected static final long INT_MAX = Integer.MAX_VALUE;
    // Unsigned Integer Max == 2^32
    protected static final long UINT_MAX = 0xFFFFFFFFL;

    protected ArrayByteBufferPool.Tracking pool;
    protected ByteBufferPool.Sized sizedPool;
    protected BrotliCompression brotli;

    @BeforeEach
    public void initPool()
    {
        pool = new ArrayByteBufferPool.Tracking();
        sizedPool = new ByteBufferPool.Sized(pool, true, 4096);
    }

    protected void startBrotli() throws Exception
    {
        startBrotli(-1);
    }

    protected void startBrotli(int bufferSize) throws Exception
    {
        brotli = new BrotliCompression();
        if (bufferSize > 0)
            brotli.setBufferSize(bufferSize);

        brotli.setByteBufferPool(pool);
        brotli.start();
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(brotli);
        assertEquals(0, pool.getLeaks().size(), () -> "LEAKS: " + pool.dumpLeaks());
    }

    public static List<String> textResources()
    {
        return List.of("texts/logo.svg", "texts/long.txt", "texts/quotes.txt");
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
        BrotliEncoderConfig brotliEncoderConfig = new BrotliEncoderConfig();
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             OutputStream output = brotli.newEncoderOutputStream(bytesOut))
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
            InputStream decoderInput = brotli.newDecoderInputStream(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            IO.copy(decoderInput, output);
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
