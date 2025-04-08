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
import java.util.List;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static org.junit.jupiter.api.Assertions.assertEquals;

public abstract class AbstractZstdTest
{
    protected ArrayByteBufferPool.Tracking pool;
    protected ByteBufferPool.Sized sizedPool;
    protected ZstandardCompression zstd;

    public static List<String> textResources()
    {
        return List.of("texts/logo.svg", "texts/long.txt", "texts/quotes.txt");
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
        zstd = new ZstandardCompression();
        zstd.setByteBufferPool(pool);
        zstd.start();
    }
}
