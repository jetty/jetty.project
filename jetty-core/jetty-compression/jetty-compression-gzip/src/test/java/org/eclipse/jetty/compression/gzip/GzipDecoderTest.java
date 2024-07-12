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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CompletableTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.eclipse.jetty.http.CompressedContentFormat.GZIP;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GzipDecoderTest extends AbstractGzipTest
{
    @Test
    public void testDecodeEmpty() throws Exception
    {
        startGzip();
        Compression.Decoder decoder = gzip.newDecoder();

        RetainableByteBuffer buf = decoder.decode(Content.Chunk.EMPTY);
        assertThat(buf, is(nullValue()));
    }

    public static Stream<Arguments> precompressedText()
    {
        return Stream.of(
            Arguments.of("precompressed/test_quotes.gz", "precompressed/test_quotes.txt")
            /*, TODO: figure out flaw in testcase with large inputs (other large input tests work)
            Arguments.of("precompressed/logo.svgz", "precompressed/logo.svg"),
            Arguments.of("precompressed/text-long.txt.gz", "precompressed/text-long.txt")
             */
        );
    }

    @ParameterizedTest
    @MethodSource("precompressedText")
    public void testDecodeText(String precompressedRef, String expectedRef) throws Exception
    {
        startGzip();
        Compression.Decoder decoder = gzip.newDecoder();
        Path inputPath = MavenPaths.findTestResourceFile(precompressedRef);

        Content.Source source = Content.Source.from(inputPath);

        StringBuilder builder = new StringBuilder();

        var task = new CompletableTask<>()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk chunk = source.read();
                    if (chunk == null)
                    {
                        source.demand(this);
                        break;
                    }

                    if (chunk.hasRemaining())
                    {
                        try
                        {
                            RetainableByteBuffer decoded = decoder.decode(chunk);
                            builder.append(BufferUtil.toString(decoded.getByteBuffer()));
                            decoded.release();
                        }
                        catch (IOException e)
                        {
                            completeExceptionally(e);
                        }
                    }
                    chunk.release();

                    if (chunk.isLast())
                    {
                        complete(null);
                        break;
                    }
                }
            }
        };
        source.demand(task);

        Path expectedPath = MavenPaths.findTestResourceFile(expectedRef);
        String expected = Files.readString(expectedPath);

        assertThat(builder.toString(), is(expected));
    }

    /**
     * TODO - this test doesn't belong here
     */
    @Test
    public void testStripSuffixes()
    {
        assertThat(GZIP.stripSuffixes("12345"), is("12345"));
        assertThat(GZIP.stripSuffixes("12345, 666" + GZIP.getEtagSuffix()), is("12345, 666"));
        assertThat(GZIP.stripSuffixes("12345, 666" + GZIP.getEtagSuffix() + ",W/\"9999" + GZIP.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }

    @Test
    public void testStreamNoBlocks() throws Exception
    {
        startGzip();
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
        startGzip();
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
        assertEquals(data, baos.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void testNoBlocks() throws Exception
    {
        startGzip();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.close();
        byte[] bytes = baos.toByteArray();

        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        assertEquals(0, decoded.remaining());
        decoded.release();
    }

    @Test
    public void testSmallBlock() throws Exception
    {
        startGzip();
        String data = "0";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream output = new GZIPOutputStream(baos);
        output.write(data.getBytes(StandardCharsets.UTF_8));
        output.close();
        byte[] bytes = baos.toByteArray();

        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        decoded.release();
    }

    @Test
    public void testSmallBlockWithGZIPChunkedAtBegin() throws Exception
    {
        startGzip();
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

        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(0, decoded.remaining());
        decoded.release();
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        decoded.release();
    }

    @Test
    public void testSmallBlockWithGZIPChunkedAtEnd() throws Exception
    {
        startGzip();
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

        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        assertFalse(decoder.isFinished());
        decoded.release();
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(0, decoded.remaining());
        assertTrue(decoder.isFinished());
        decoded.release();
    }

    @Test
    public void testSmallBlockWithGZIPTrailerChunked() throws Exception
    {
        startGzip();
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

        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes1));
        assertEquals(0, decoded.remaining());
        decoded.release();
        decoded = decoder.decode(ByteBuffer.wrap(bytes2));
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        decoded.release();
    }

    @Test
    public void testTwoSmallBlocks() throws Exception
    {
        startGzip();
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

        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        RetainableByteBuffer decoded = decoder.decode(buffer);
        assertEquals(data1, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        assertTrue(decoder.isFinished());
        assertTrue(buffer.hasRemaining());
        decoded.release();
        decoded = decoder.decode(buffer);
        assertEquals(data2, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        assertTrue(decoder.isFinished());
        assertFalse(buffer.hasRemaining());
        decoded.release();
    }

    @Test
    public void testBigBlock() throws Exception
    {
        startGzip();
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
        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            RetainableByteBuffer decoded = decoder.decode(buffer);
            result += StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString();
            decoded.release();
        }
        assertEquals(data, result);
    }

    @Test
    public void testBigBlockOneByteAtATime() throws Exception
    {
        startGzip(64);
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
        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            RetainableByteBuffer decoded = decoder.decode(ByteBuffer.wrap(new byte[]{buffer.get()}));
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString();
            decoded.release();
        }
        assertEquals(data, result);
        assertTrue(decoder.isFinished());
    }

    @Test
    public void testBigBlockWithExtraBytes() throws Exception
    {
        startGzip(64);
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
        GzipDecoder decoder = (GzipDecoder)gzip.newDecoder();
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            RetainableByteBuffer decoded = decoder.decode(buffer);
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString();
            decoded.release();
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
    public void testLargeGzipStream(long origSize) throws Exception
    {
        // Size chosen for trade off between speed of I/O vs speed of Gzip
        final int BUFSIZE = 64 * 1024 * 1024;
        startGzip(BUFSIZE);

        // Create a buffer to use over and over again to produce the uncompressed input
        byte[] cbuf = "0123456789ABCDEFGHIJKLMOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[BUFSIZE];
        for (int off = 0; off < buf.length; )
        {
            int len = Math.min(cbuf.length, buf.length - off);
            System.arraycopy(cbuf, 0, buf, off, len);
            off += len;
        }

        GzipDecoderOutputStream out = new GzipDecoderOutputStream((GzipDecoder)gzip.newDecoder());
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

    public static class GzipDecoderOutputStream extends OutputStream
    {
        private final GzipDecoder decoder;
        public long decodedByteCount = 0L;

        public GzipDecoderOutputStream(GzipDecoder decoder)
        {
            this.decoder = decoder;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            while (buf.hasRemaining())
            {
                RetainableByteBuffer decoded = decoder.decode(buf);
                if (decoded.hasRemaining())
                {
                    decodedByteCount += decoded.remaining();
                }
                decoded.release();
            }
        }

        @Override
        public void write(int b) throws IOException
        {
            write(new byte[]{(byte)b}, 0, 1);
        }
    }
}
