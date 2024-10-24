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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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

public abstract class AbstractZstdTest
{
    // Signed Integer Max
    protected static final long INT_MAX = Integer.MAX_VALUE;
    // Unsigned Integer Max == 2^32
    protected static final long UINT_MAX = 0xFFFFFFFFL;
    protected static final ByteBuffer EMPTY_DIRECT_BUFFER = ByteBuffer.allocateDirect(0);

    protected ArrayByteBufferPool.Tracking pool;
    protected ByteBufferPool.Sized sizedPool;
    protected ZstandardCompression zstd;

    public static List<String> textResources()
    {
        return List.of("texts/logo.svg", "texts/long.txt", "texts/quotes.txt");
    }

    /**
     * Create a Direct ByteBuffer from a byte array.
     *
     * <p>
     * This is a replacement of {@link ByteBuffer#wrap(byte[])} but
     * for producing Direct {@link ByteBuffer} implementations that
     * {@code zstd-jni} require.
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
     * Create a Direct ByteBuffer from a String.
     *
     * @param str the string to use for bytes. (using {@link StandardCharsets#UTF_8})
     * @return the Direct ByteBuffer representing the byte array.
     */
    public ByteBuffer asDirect(String str)
    {
        return asDirect(str.getBytes(UTF_8));
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
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             OutputStream output = zstd.newEncoderOutputStream(bytesOut))
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
            InputStream decoderInput = zstd.newDecoderInputStream(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            IO.copy(decoderInput, output);
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

    @BeforeEach
    public void initPool()
    {
        pool = new ArrayByteBufferPool.Tracking();
        sizedPool = new ByteBufferPool.Sized(pool, true, 4096);
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(zstd);
        assertEquals(0, pool.getLeaks().size(), () -> "LEAKS: " + pool.dumpLeaks());
    }

    protected void startZstd() throws Exception
    {
        startZstd(-1);
    }

    protected void startZstd(int bufferSize) throws Exception
    {
        zstd = new ZstandardCompression();
        if (bufferSize > 0)
            zstd.setBufferSize(bufferSize);

        zstd.setByteBufferPool(pool);
        zstd.start();
    }
}
