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
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GzipCompressionTest extends AbstractGzipTest
{
    /**
     * Proof that the {@link GZIPInputStream} can read an entire block of GZIP compressed content
     * (headers + data + trailers) that is followed by non GZIP content.
     * The extra content is not read, and no Exception is thrown.
     */
    @Test
    public void testBigBlockWithExtraBytesViaGzipInputStream() throws Exception
    {
        startGzip(64);
        String data1 = "0123456789ABCDEF".repeat(10);
        ByteBuffer bytes1 = ByteBuffer.wrap(compress(data1));
        String data2 = "HELLO";
        ByteBuffer bytes2 = ByteBuffer.wrap(data2.getBytes(UTF_8));

        ByteBuffer bytes = ByteBuffer.allocate(bytes1.remaining() + bytes2.remaining());
        bytes.put(bytes1.slice());
        bytes.put(bytes2.slice());
        BufferUtil.flipToFlush(bytes, 0);

        byte[] bigblockwithextra = BufferUtil.toArray(bytes);

        try (ByteArrayInputStream in = new ByteArrayInputStream(bigblockwithextra);
             GZIPInputStream gzipIn = new GZIPInputStream(in))
        {
            String decoded = IO.toString(gzipIn, UTF_8);
            assertEquals(data1, decoded);
            // the extra data2 is not read, and there is no exception.
        }
    }

    @Test
    public void testStripSuffixes() throws Exception
    {
        startGzip();
        assertThat(gzip.stripSuffixes("12345"), is("12345"));
        assertThat(gzip.stripSuffixes("12345, 666" + gzip.getEtagSuffix()), is("12345, 666"));
        assertThat(gzip.stripSuffixes("12345, 666" + gzip.getEtagSuffix() + ",W/\"9999" + gzip.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }

    /**
     * Proof that the {@link GZIPInputStream} can read multiple entire blocks of GZIP compressed content (headers + data + trailers)
     * as a single set of decoded data, and does not terminate at the first {@link Inflater#finished()} or when reaching the
     * first GZIP trailer.
     */
    @Test
    public void testTwoSmallBlocksViaGzipInputStream() throws Exception
    {
        startGzip();
        String data1 = "0";
        // Entire Gzip Buffer (headers + content + trailers)
        ByteBuffer bytes1 = ByteBuffer.wrap(compress(data1));
        String data2 = "1";
        // Yet another entire Gzip Buffer (headers + content + trailers)
        ByteBuffer bytes2 = ByteBuffer.wrap(compress(data2));

        // Buffer containing 2 entire gzip compressions
        ByteBuffer bytes = ByteBuffer.allocate(bytes1.remaining() + bytes2.remaining());

        bytes.put(bytes1.slice());
        bytes.put(bytes2.slice());

        BufferUtil.flipToFlush(bytes, 0);

        byte[] twoblocks = BufferUtil.toArray(bytes);

        try (ByteArrayInputStream in = new ByteArrayInputStream(twoblocks);
             GZIPInputStream gzipIn = new GZIPInputStream(in))
        {
            String decoded = IO.toString(gzipIn, UTF_8);
            assertEquals(data1 + data2, decoded);
        }
    }
}
