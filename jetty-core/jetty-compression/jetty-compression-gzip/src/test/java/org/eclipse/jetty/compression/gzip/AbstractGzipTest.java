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
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

public abstract class AbstractGzipTest
{
    protected ArrayByteBufferPool.Tracking trackingPool;
    protected ByteBufferPool.Sized sizedPool;
    protected GzipCompression gzip;

    public static List<String> textResources()
    {
        return List.of("texts/logo.svg", "texts/long.txt", "texts/quotes.txt");
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

    @BeforeEach
    public void initPool()
    {
        trackingPool = new ArrayByteBufferPool.Tracking();
        sizedPool = new ByteBufferPool.Sized(trackingPool, true, 4096);
    }

    @AfterEach
    public void tearDown()
    {
        LifeCycle.stop(gzip);
        assertEquals(0, trackingPool.getLeaks().size(), () -> "LEAKS: " + trackingPool.dumpLeaks());
    }

    protected void startGzip() throws Exception
    {
        gzip = new GzipCompression();
        gzip.setBufferPool(WritableBufferPool.wrap(trackingPool));
        gzip.start();
    }
}
