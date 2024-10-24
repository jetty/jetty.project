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

package org.eclipse.jetty.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.compression.brotli.BrotliCompression;
import org.eclipse.jetty.compression.gzip.GzipCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.provider.Arguments;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public abstract class AbstractCompressionTest
{
    protected Compression compression;
    protected ArrayByteBufferPool.Tracking pool;

    public static List<Class<? extends Compression>> compressions()
    {
        return List.of(
            GzipCompression.class,
            BrotliCompression.class,
            ZstandardCompression.class
        );
    }

    public static Stream<Arguments> textInputs()
    {
        List<Arguments> cases = new ArrayList<>();
        List<String> texts = List.of("texts/quotes.txt", "texts/long.txt", "texts/logo.svg");

        for (Class<? extends Compression> compressionClass : compressions())
        {
            for (String text : texts)
            {
                cases.add(Arguments.of(compressionClass, text));
            }
        }

        return cases.stream();
    }

    /**
     * Create a Direct ByteBuffer from a byte array.
     *
     * <p>
     * This is a replacement of {@link ByteBuffer#wrap(byte[])} but
     * for producing Direct {@link ByteBuffer} implementations that
     * some compression libs require (eg: {@code zstd-jni})
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
     * Generate compressed bytes using Compression specific OutputStream
     *
     * @param data the data to compress
     * @return the compressed bytes
     * @throws IOException if unable to compress input data
     */
    public byte[] compress(byte[] data) throws IOException
    {
        Assertions.assertNotNull(compression, "Compression implementation not started yet");

        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             OutputStream output = compression.newEncoderOutputStream(bytesOut))
        {
            if (data != null)
                output.write(data);
            output.close();
            return bytesOut.toByteArray();
        }
    }

    /**
     * Generate compressed bytes using Compression specific OutputStream
     *
     * @param data the data to compress
     * @return the compressed bytes
     * @throws IOException if unable to compress input data
     */
    public byte[] compress(String data) throws IOException
    {
        return compress(data.getBytes(UTF_8));
    }

    /**
     * Decompress bytes using Compression specific InputStream
     *
     * @param compressedBytes the data to decompress
     * @return the decompressed bytes
     * @throws IOException if unable to decompress
     */
    public byte[] decompress(byte[] compressedBytes) throws IOException
    {
        Assertions.assertNotNull(compression, "Compression implementation not started yet");
        Assertions.assertNotNull(compressedBytes, "compressedBytes");

        try (ByteArrayInputStream input = new ByteArrayInputStream(compressedBytes);
             InputStream compressionInput = compression.newDecoderInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            IO.copy(compressionInput, output);
            return output.toByteArray();
        }
    }

    /**
     * Decompress ByteBuffer using Compression specific InputStream implementation.
     *
     * @param compressedBytes the data to decompress
     * @return the decompressed bytes
     * @throws IOException if unable to decompress
     */
    public byte[] decompress(ByteBuffer compressedBytes) throws IOException
    {
        return decompress(BufferUtil.toArray(compressedBytes));
    }

    @AfterEach
    public void stopCompression()
    {
        LifeCycle.stop(compression);
        if (pool != null)
            assertEquals(0, pool.getLeaks().size(), () -> "LEAKS: " + pool.dumpLeaks());
    }

    protected void newCompression(String compressionType) throws Exception
    {
        switch (compressionType)
        {
            case "br" -> newCompression(BrotliCompression.class);
            case "zstandard" -> newCompression(ZstandardCompression.class);
            case "gzip" -> newCompression(GzipCompression.class);
            default -> fail("Unrecognized compressionType: " + compressionType);
        };
    }

    protected void newCompression(Class<? extends Compression> compressionClass) throws Exception
    {
        compression = compressionClass.getDeclaredConstructor().newInstance();
        pool = new ArrayByteBufferPool.Tracking();
        compression.setByteBufferPool(pool);
    }

    protected void startCompression(Class<? extends Compression> compressionClass, int bufferSize) throws Exception
    {
        newCompression(compressionClass);
        if (bufferSize > 0)
            compression.setBufferSize(bufferSize);
        compression.start();
    }

    protected void startCompression(Class<? extends Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass, -1);
    }
}
