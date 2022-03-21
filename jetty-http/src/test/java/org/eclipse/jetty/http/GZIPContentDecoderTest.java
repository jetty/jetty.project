//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.eclipse.jetty.http.CompressedContentFormat.BR;
import static org.eclipse.jetty.http.CompressedContentFormat.GZIP;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GZIPContentDecoderTest
{
    private ArrayByteBufferPool pool;
    private AtomicInteger buffers = new AtomicInteger(0);

    @BeforeEach
    public void before()
    {
        buffers.set(0);
        pool = new ArrayByteBufferPool()
        {

            @Override
            public ByteBuffer acquire(int size, boolean direct)
            {
                buffers.incrementAndGet();
                return super.acquire(size, direct);
            }

            @Override
            public void release(ByteBuffer buffer)
            {
                buffers.decrementAndGet();
                super.release(buffer);
            }
        };
    }

    @AfterEach
    public void after()
    {
        assertEquals(0, buffers.get());
    }

    @Test
    public void testCompressedContentFormat()
    {
        assertTrue(CompressedContentFormat.tagEquals("tag", "tag"));
        assertTrue(CompressedContentFormat.tagEquals("\"tag\"", "\"tag\""));
        assertTrue(CompressedContentFormat.tagEquals("\"tag\"", "\"tag" + GZIP.getEtagSuffix() + "\""));
        assertTrue(CompressedContentFormat.tagEquals("\"tag\"", "\"tag" + BR.getEtagSuffix() + "\""));
        assertTrue(CompressedContentFormat.tagEquals("W/\"1234567\"", "W/\"1234567\""));
        assertTrue(CompressedContentFormat.tagEquals("W/\"1234567\"", "W/\"1234567" + GZIP.getEtagSuffix() + "\""));

        assertFalse(CompressedContentFormat.tagEquals("Zag", "Xag" + GZIP.getEtagSuffix()));
        assertFalse(CompressedContentFormat.tagEquals("xtag", "tag"));
        assertFalse(CompressedContentFormat.tagEquals("W/\"1234567\"", "W/\"1234111\""));
        assertFalse(CompressedContentFormat.tagEquals("W/\"1234567\"", "W/\"1234111" + GZIP.getEtagSuffix() + "\""));

        assertTrue(CompressedContentFormat.tagEquals("12345", "\"12345\""));
        assertTrue(CompressedContentFormat.tagEquals("\"12345\"", "12345"));
        assertTrue(CompressedContentFormat.tagEquals("12345", "\"12345" + GZIP.getEtagSuffix() + "\""));
        assertTrue(CompressedContentFormat.tagEquals("\"12345\"", "12345" + GZIP.getEtagSuffix()));

        assertThat(GZIP.stripSuffixes("12345"), is("12345"));
        assertThat(GZIP.stripSuffixes("12345, 666" + GZIP.getEtagSuffix()), is("12345, 666"));
        assertThat(GZIP.stripSuffixes("12345, 666" + GZIP.getEtagSuffix() + ",W/\"9999" + GZIP.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }

    @Test
    public void testStreamNoBlocks() throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.close();
        byte[] bytes = baos.toByteArray();

        GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes), 1);
        int read = input.read();
        assertEquals(-1, read);
    }

    @Test
    public void testStreamBigBlockOneByteAtATime() throws Exception
    {
        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        baos = new ByteArrayOutputStream();
        GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(bytes), 1);
        int read;
        while ((read = input.read()) >= 0)
        {
            baos.write(read);
        }
        assertEquals(data, new String(baos.toByteArray(), StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testNoBlocks(boolean useDirectBuffers) throws Exception
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.close();
        byte[] bytes = baos.toByteArray();

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool, 2048, useDirectBuffers);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        assertEquals(0, decoded.remaining());
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testSmallBlock(boolean useDirectBuffers) throws Exception
    {
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool, 2048, useDirectBuffers);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded).toString());
        decoder.release(decoded);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testSmallBlockWithGZIPChunkedAtBegin(boolean useDirectBuffers) throws Exception
    {
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // The header is 10 bytes, chunk at 11 bytes
        byte[] bytes1 = new byte[11];
        System.arraycopy(bytes, 0, bytes1, 0, bytes1.length);
        byte[] bytes2 = new byte[bytes.length - bytes1.length];
        System.arraycopy(bytes, bytes1.length, bytes2, 0, bytes2.length);

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool, 2048, useDirectBuffers);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(0, decoded.capacity());
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded).toString());
        decoder.release(decoded);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testSmallBlockWithGZIPChunkedAtEnd(boolean useDirectBuffers) throws Exception
    {
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // The trailer is 8 bytes, chunk the last 9 bytes
        byte[] bytes1 = new byte[bytes.length - 9];
        System.arraycopy(bytes, 0, bytes1, 0, bytes1.length);
        byte[] bytes2 = new byte[bytes.length - bytes1.length];
        System.arraycopy(bytes, bytes1.length, bytes2, 0, bytes2.length);

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool, 2048, useDirectBuffers);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded).toString());
        assertFalse(decoder.isFinished());
        decoder.release(decoded);
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(0, decoded.remaining());
        assertTrue(decoder.isFinished());
        decoder.release(decoded);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testSmallBlockWithGZIPTrailerChunked(boolean useDirectBuffers) throws Exception
    {
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        // The trailer is 4+4 bytes, chunk the last 3 bytes
        byte[] bytes1 = new byte[bytes.length - 3];
        System.arraycopy(bytes, 0, bytes1, 0, bytes1.length);
        byte[] bytes2 = new byte[bytes.length - bytes1.length];
        System.arraycopy(bytes, bytes1.length, bytes2, 0, bytes2.length);

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool, 2048, useDirectBuffers);
        ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(0, decoded.capacity());
        decoder.release(decoded);
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded).toString());
        decoder.release(decoded);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testTwoSmallBlocks(boolean useDirectBuffers) throws Exception
    {
        String data1 = "0";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data1.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes1 = baos.toByteArray();

        String data2 = "1";
        baos = new ByteArrayOutputStream();
        output = new GZIPOutputStream(baos);
        output.write(data2.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes2 = baos.toByteArray();

        byte[] bytes = new byte[bytes1.length + bytes2.length];
        System.arraycopy(bytes1, 0, bytes, 0, bytes1.length);
        System.arraycopy(bytes2, 0, bytes, bytes1.length, bytes2.length);

        GZIPContentDecoder decoder = new GZIPContentDecoder(pool, 2048, useDirectBuffers);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        ByteBuffer decoded = decoder.decode(buffer);
        assertEquals(data1, StandardCharsets.UTF_8.decode(decoded).toString());
        assertTrue(decoder.isFinished());
        assertTrue(buffer.hasRemaining());
        decoder.release(decoded);
        decoded = decoder.decode(buffer);
        assertEquals(data2, StandardCharsets.UTF_8.decode(decoded).toString());
        assertTrue(decoder.isFinished());
        assertFalse(buffer.hasRemaining());
        decoder.release(decoded);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    public void testBigBlock(boolean useDirectBuffers) throws Exception
    {
        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        String result = "";
        GZIPContentDecoder decoder = new GZIPContentDecoder(pool, 2048, useDirectBuffers);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            ByteBuffer decoded = decoder.decode(buffer);
            result += StandardCharsets.UTF_8.decode(decoded).toString();
            decoder.release(decoded);
        }
        assertEquals(data, result);
    }

    @Test
    public void testBigBlockOneByteAtATime() throws Exception
    {
        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        String result = "";
        GZIPContentDecoder decoder = new GZIPContentDecoder(64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            ByteBuffer decoded = decoder.decode(ByteBuffer.wrap(new byte[]{buffer.get()}));
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded).toString();
            decoder.release(decoded);
        }
        assertEquals(data, result);
        assertTrue(decoder.isFinished());
    }

    @Test
    public void testBigBlockWithExtraBytes() throws Exception
    {
        String data1 = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
        {
            data1 += data1;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data1.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes1 = baos.toByteArray();

        String data2 = "HELLO";
        byte[] bytes2 = data2.getBytes(StandardCharsets.UTF_8);

        byte[] bytes = new byte[bytes1.length + bytes2.length];
        System.arraycopy(bytes1, 0, bytes, 0, bytes1.length);
        System.arraycopy(bytes2, 0, bytes, bytes1.length, bytes2.length);

        String result = "";
        GZIPContentDecoder decoder = new GZIPContentDecoder(64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            ByteBuffer decoded = decoder.decode(buffer);
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded).toString();
            decoder.release(decoded);
            if (decoder.isFinished())
                break;
        }
        assertEquals(data1, result);
        assertTrue(buffer.hasRemaining());
        assertEquals(data2, StandardCharsets.UTF_8.decode(buffer).toString());
    }

    // Signed Integer Max
    static final long INT_MAX = Integer.MAX_VALUE;

    // Unsigned Integer Max == 2^32
    static final long UINT_MAX = 0xFFFFFFFFL;

    @ParameterizedTest
    @ValueSource(longs = {INT_MAX, INT_MAX + 1 /* TODO too slow , UINT_MAX, UINT_MAX + 1 */ })
    public void testLargeGzipStream(long origSize) throws IOException
    {
        // Size chosen for trade off between speed of I/O vs speed of Gzip
        final int BUFSIZE = 64 * 1024 * 1024;

        // Create a buffer to use over and over again to produce the uncompressed input
        byte[] cbuf = "0123456789ABCDEFGHIJKLMOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[BUFSIZE];
        for (int off = 0; off < buf.length; )
        {
            int len = Math.min(cbuf.length, buf.length - off);
            System.arraycopy(cbuf, 0, buf, off, len);
            off += len;
        }

        GZIPDecoderOutputStream out = new GZIPDecoderOutputStream(new GZIPContentDecoder(BUFSIZE));
        GZIPOutputStream outputStream = new GZIPOutputStream(out, BUFSIZE);

        for (long bytesLeft = origSize; bytesLeft > 0; )
        {
            int len = buf.length;
            if (bytesLeft < buf.length)
            {
                len = (int)bytesLeft;
            }
            outputStream.write(buf, 0, len);
            bytesLeft -= len;
        }

        // Close GZIPOutputStream to have it generate gzip trailer.
        // This can cause more writes of unflushed gzip buffers
        outputStream.close();

        // out.decodedByteCount is only valid after close
        assertThat("Decoded byte count", out.decodedByteCount, is(origSize));
    }

    public static class GZIPDecoderOutputStream extends OutputStream
    {
        private final GZIPContentDecoder decoder;
        public long decodedByteCount = 0L;

        public GZIPDecoderOutputStream(GZIPContentDecoder decoder)
        {
            this.decoder = decoder;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            while (buf.hasRemaining())
            {
                ByteBuffer decoded = decoder.decode(buf);
                if (decoded.hasRemaining())
                {
                    decodedByteCount += decoded.remaining();
                }
                decoder.release(decoded);
            }
        }

        @Override
        public void write(int b) throws IOException
        {
            write(new byte[]{(byte)b}, 0, 1);
        }
    }
}
