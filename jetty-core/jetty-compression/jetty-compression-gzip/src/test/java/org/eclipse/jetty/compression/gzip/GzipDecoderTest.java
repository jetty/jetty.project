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
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class GzipDecoderTest extends AbstractGzipTest
{
    @Test
    public void testBigBlock() throws Exception
    {
        startGzip();
        String data = "0123456789ABCDEF".repeat(10);
        ByteBuffer bytes = ByteBuffer.wrap(compress(data));

        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            StringBuilder actual = new StringBuilder();

            RetainableByteBuffer decoded = decoder.decode(bytes);
            actual.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            assertEquals(data, actual.toString());
        }
    }

    @Test
    public void testBigBlockOneByteAtATime() throws Exception
    {
        startGzip();

        String data = "0123456789ABCDEF".repeat(10);
        byte[] compressedBytes = compress(data);

        // Using built-in GZIP features.
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             GZIPInputStream input = new GZIPInputStream(bytesIn, compressedBytes.length))
        {
            int read;
            while ((read = input.read()) >= 0)
            {
                bytesOut.write(read);
            }
            assertEquals(data, bytesOut.toString(UTF_8));
        }

        // Using GzipDecoder.
        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            StringBuilder actualContent = new StringBuilder();
            ByteBuffer compressed = ByteBuffer.wrap(compressedBytes);

            while (compressed.hasRemaining())
            {
                // decode 1 byte at a time for this test
                ByteBuffer singleByte = ByteBuffer.wrap(new byte[]{compressed.get()});
                RetainableByteBuffer output = decoder.decode(singleByte);
                assertThat(output, notNullValue());
                if (output.hasRemaining())
                {
                    actualContent.append(BufferUtil.toString(output.getByteBuffer(), UTF_8));
                }
                output.release();
            }

            assertEquals(data, actualContent.toString());
        }
    }

    @Test
    public void testBigBlockWithExtraBytes() throws Exception
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

        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            StringBuilder actual = new StringBuilder();

            while (bytes.hasRemaining())
            {
                RetainableByteBuffer decoded = decoder.decode(bytes);
                actual.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
                decoded.release();
                if (decoder.isFinished())
                    break;
            }

            assertEquals(data1, actual.toString());
            assertTrue(bytes.hasRemaining());
            assertEquals(data2, UTF_8.decode(bytes).toString());
        }
    }

    @Test
    public void testDecodeEmpty() throws Exception
    {
        startGzip();
        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            RetainableByteBuffer buf = decoder.decode(BufferUtil.EMPTY_BUFFER);
            assertThat(buf, is(notNullValue()));
            assertThat(buf.getByteBuffer().hasRemaining(), is(false));
            assertThat("Decoder hasn't reached the end of the compressed content", decoder.isFinished(), is(false));
        }
    }

    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = false, delimiterString = "|", textBlock = """
       # PRECOMPRESSED                  | UNCOMPRESSED            
       precompressed/test_quotes.txt.gz | precompressed/test_quotes.txt
       precompressed/logo.svgz          | precompressed/logo.svg
       precompressed/text-long.txt.gz   | precompressed/text-long.txt
       """)
    public void testDecodeText(String precompressedRef, String expectedRef) throws Exception
    {
        startGzip();
        Path inputPath = MavenPaths.findTestResourceFile(precompressedRef);

        StringBuilder result = new StringBuilder();

        try (Compression.Decoder decoder = gzip.newDecoder();
             SeekableByteChannel inputChannel = Files.newByteChannel(inputPath, StandardOpenOption.READ))
        {
            ByteBuffer readBuffer = ByteBuffer.allocate(2048);

            boolean done = false;
            while (!done)
            {
                BufferUtil.clearToFill(readBuffer);
                try
                {
                    int len = inputChannel.read(readBuffer);

                    if (len == -1)
                    {
                        done = true;
                        break;
                    }
                    if (len > 0)
                    {
                        BufferUtil.flipToFlush(readBuffer, 0);
                        RetainableByteBuffer decoded = decoder.decode(readBuffer);
                        result.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
                        decoded.release();
                    }
                }
                catch (IOException e)
                {
                    fail(e);
                }
            }
        }

        Path expectedPath = MavenPaths.findTestResourceFile(expectedRef);
        String expected = Files.readString(expectedPath);

        assertEquals(expected, result.toString());
    }

    @ParameterizedTest
    @ValueSource(longs = {
        INT_MAX / 2,
        /* TODO too slow ,
        INT_MAX,
        INT_MAX + 1,
        UINT_MAX,
        UINT_MAX + 1
         */
    })
    @Disabled("too slow for short term, enable again for long term")
    public void testLargeGzipStream(long origSize) throws Exception
    {
        // Size chosen for trade off between speed of I/O vs speed of Gzip
        final int BUFSIZE = 64 * 1024 * 1024;
        startGzip(BUFSIZE);

        // Create a buffer to use over and over again to produce the uncompressed input
        byte[] cbuf = "0123456789ABCDEFGHIJKLMOPQRSTUVWXYZ".getBytes(UTF_8);
        byte[] buf = new byte[BUFSIZE];
        for (int off = 0; off < buf.length; )
        {
            int len = Math.min(cbuf.length, buf.length - off);
            System.arraycopy(cbuf, 0, buf, off, len);
            off += len;
        }

        // Perform Built-in Compression to immediate Compression.Decoder decode
        // with counting of decoded bytes.
        try (Compression.Decoder decoder = gzip.newDecoder();
             GzipDecoderOutputStream out = new GzipDecoderOutputStream(decoder);
             GZIPOutputStream outputStream = new GZIPOutputStream(out, BUFSIZE))
        {
            int offset = 0;
            long bytesLeft = origSize;
            while (bytesLeft > 0)
            {
                int len = buf.length;
                if (bytesLeft < buf.length)
                {
                    len = (int)bytesLeft;
                }
                outputStream.write(buf, offset, len);
                bytesLeft -= len;
            }

            // Close GZIPOutputStream to have it generate gzip trailer.
            // This can cause more writes of unflushed gzip buffers
            outputStream.close();

            // out.decodedByteCount is only valid after close
            assertThat("Decoded byte count", out.decodedByteCount, is(origSize));
        }
    }

    @Test
    public void testNoBlocks() throws Exception
    {
        startGzip();

        byte[] compressedBytes = compress(null);

        // Compressed bytes should have some content, and not be totally blank.
        assertThat(compressedBytes.length, greaterThan(8));

        // Using built-in GZIP features, this should result in an immediate EOF (read == -1).
        try (ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             GZIPInputStream input = new GZIPInputStream(bytesIn, compressedBytes.length))
        {
            int read = input.read();
            assertEquals(-1, read, "Expected EOF");
        }

        // Using GzipDecoder this should result in no content
        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            ByteBuffer compressed = ByteBuffer.wrap(compressedBytes);
            RetainableByteBuffer output = decoder.decode(compressed);
            assertThat(output, notNullValue());
            assertThat(output.getByteBuffer().remaining(), is(0));
            assertThat(decoder.isFinished(), is(true));
            output.release();
        }
    }

    @Test
    public void testOneEmptyBlock() throws Exception
    {
        startGzip();
        byte[] compressedBytes = compress("");

        // Compressed bytes should have some content, and not be totally blank.
        assertThat(compressedBytes.length, greaterThan(8));

        // Using built-in GZIP features, this should result in an immediate EOF (read == -1).
        try (ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             GZIPInputStream input = new GZIPInputStream(bytesIn, compressedBytes.length))
        {
            int read = input.read();
            assertEquals(-1, read, "Expected EOF");
        }

        // Using GzipDecoder this should result in no content
        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            ByteBuffer compressed = ByteBuffer.wrap(compressedBytes);
            RetainableByteBuffer output = decoder.decode(compressed);
            assertThat(output, notNullValue());
            assertThat(output.getByteBuffer().remaining(), is(0));
            assertThat(decoder.isFinished(), is(true));
            output.release();
        }
    }

    @Test
    public void testSmallBlock() throws Exception
    {
        startGzip();
        String data = "0";
        byte[] compressedBytes = compress(data);

        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            ByteBuffer compressed = ByteBuffer.wrap(compressedBytes);
            RetainableByteBuffer decoded = decoder.decode(compressed);
            assertEquals(data, BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();
        }
    }

    @Test
    public void testSmallBlockWithGZIPChunkedAtBegin() throws Exception
    {
        startGzip();
        String data = "0";
        ByteBuffer bytes = ByteBuffer.wrap(compress(data));

        // The header is 10 bytes, chunk at 11 bytes
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(11);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(11);

        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            StringBuilder actual = new StringBuilder();

            RetainableByteBuffer decoded = decoder.decode(slice1);
            assertNotNull(decoded);
            assertThat(decoder.isFinished(), is(false));
            actual.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            decoded = decoder.decode(slice2);
            assertNotNull(decoded);
            assertThat(decoder.isFinished(), is(true));
            actual.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            assertEquals(data, actual.toString());
        }
    }

    @Test
    public void testSmallBlockWithGZIPChunkedAtEnd() throws Exception
    {
        startGzip();
        String data = "0";
        ByteBuffer bytes = ByteBuffer.wrap(compress(data));

        // The trailer is 8 bytes, chunk the last 9 bytes
        int split = bytes.remaining() - 9;
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            StringBuilder actual = new StringBuilder();

            RetainableByteBuffer decoded = decoder.decode(slice1);
            assertNotNull(decoded);
            assertThat(decoder.isFinished(), is(false)); // haven't read the trailers yet
            actual.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            decoded = decoder.decode(slice2);
            assertNotNull(decoded);
            assertThat(decoder.isFinished(), is(true)); // we reached the end
            actual.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            assertEquals(data, actual.toString());
        }
    }

    @Test
    public void testSmallBlockWithGZIPTrailerChunked() throws Exception
    {
        startGzip();
        String data = "0";
        ByteBuffer bytes = ByteBuffer.wrap(compress(data));

        // The trailer is 4+4 bytes, chunk the last 3 bytes
        int split = bytes.remaining() - 3;
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            StringBuilder actual = new StringBuilder();

            RetainableByteBuffer decoded = decoder.decode(slice1);
            assertNotNull(decoded);
            assertThat(decoder.isFinished(), is(false)); // haven't read all the trailers yet
            actual.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            decoded = decoder.decode(slice2);
            assertNotNull(decoded);
            assertThat(decoder.isFinished(), is(true)); // we reached the end
            actual.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            assertEquals(data, actual.toString());
        }
    }

    @Test
    public void testStripSuffixes()
    {
        GzipCompression gzip = new GzipCompression();
        assertThat(gzip.stripSuffixes("12345"), is("12345"));
        assertThat(gzip.stripSuffixes("12345, 666" + gzip.getEtagSuffix()), is("12345, 666"));
        assertThat(gzip.stripSuffixes("12345, 666" + gzip.getEtagSuffix() + ",W/\"9999" + gzip.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }

    @Test
    public void testTwoSmallBlocks() throws Exception
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

        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            RetainableByteBuffer.DynamicCapacity decoded = new RetainableByteBuffer.DynamicCapacity();
            RetainableByteBuffer part;

            part = decoder.decode(bytes);
            assertTrue(decoder.isFinished());
            decoded.append(part);
            part.release();

            part = decoder.decode(bytes);
            assertTrue(decoder.isFinished());
            decoded.append(part);
            part.release();

            assertEquals(data1 + data2, BufferUtil.toString(decoded.getByteBuffer(), UTF_8));

            decoded.release();
        }
    }

    public static class GzipDecoderOutputStream extends OutputStream
    {
        private final Compression.Decoder decoder;
        public long decodedByteCount = 0L;

        public GzipDecoderOutputStream(Compression.Decoder decoder)
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
                decodedByteCount += decoded.remaining();
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
