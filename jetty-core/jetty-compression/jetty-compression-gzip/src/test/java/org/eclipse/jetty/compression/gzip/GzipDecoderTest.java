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
import java.util.zip.Inflater;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Disabled("Individual tests to be moved to jetty-compression-tests")
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
            actual.append(UTF_8.decode(decoded.getByteBuffer()));
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
                    actualContent.append(UTF_8.decode(output.getByteBuffer()));
                }
                output.release();
            }
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");

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
                actual.append(UTF_8.decode(decoded.getByteBuffer()));
                decoded.release();
            }
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");

            assertEquals(data1, actual.toString());
            // The extra bytes should not fail the decoder.
            // Some gzip implementations will not read the extra bytes,
            // other implementations will read and discard these extra bytes.
            // either way, it does not trigger a failure.
            // Just check that the extra bytes do not show up in the output.
            assertThat(actual.toString(), not(containsString(data2)));
        }
    }

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
    public void testDecodeEmpty() throws Exception
    {
        startGzip();
        try (Compression.Decoder decoder = gzip.newDecoder())
        {
            RetainableByteBuffer buf = decoder.decode(BufferUtil.EMPTY_BUFFER);
            assertThat(buf, is(notNullValue()));
            assertThat(buf.getByteBuffer().hasRemaining(), is(false));
            IOException ioException = assertThrows(IOException.class, () -> decoder.finishInput());
            assertThat(ioException.getMessage(), startsWith("Decoder failure"));
            buf.release();
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
            RetainableByteBuffer readRetainableBuffer = gzip.acquireByteBuffer(2048);
            try
            {
                ByteBuffer readBuffer = readRetainableBuffer.getByteBuffer();

                boolean readDone = false;
                while (!readDone)
                {
                    try
                    {
                        if (!readDone)
                        {
                            readBuffer.clear();
                            int len = inputChannel.read(readBuffer);

                            if (len == -1)
                            {
                                readDone = true;
                                decoder.finishInput();
                            }
                            else if (len > 0)
                            {
                                readBuffer.flip();
                                while (readBuffer.hasRemaining())
                                {
                                    RetainableByteBuffer decoded = decoder.decode(readBuffer);
                                    if (decoded.hasRemaining())
                                        result.append(UTF_8.decode(decoded.getByteBuffer()));
                                    decoded.release();
                                }
                            }
                        }
                        else
                        {
                            // decode remaining bytes
                            while (!decoder.isOutputComplete())
                            {
                                RetainableByteBuffer decoded = decoder.decode(BufferUtil.EMPTY_BUFFER);
                                if (decoded.hasRemaining())
                                    result.append(UTF_8.decode(decoded.getByteBuffer()));
                                decoded.release();
                            }
                        }
                    }
                    catch (IOException e)
                    {
                        fail(e);
                    }
                }
            }
            finally
            {
                readRetainableBuffer.release();
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
            output.release();
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
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
            output.release();
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
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
            assertEquals(data, UTF_8.decode(decoded.getByteBuffer()).toString());
            decoded.release();
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
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
            actual.append(UTF_8.decode(decoded.getByteBuffer()));
            decoded.release();

            decoded = decoder.decode(slice2);
            assertNotNull(decoded);
            actual.append(UTF_8.decode(decoded.getByteBuffer()));
            decoded.release();

            assertEquals(data, actual.toString());
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
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
            actual.append(UTF_8.decode(decoded.getByteBuffer()));
            decoded.release();

            decoded = decoder.decode(slice2);
            assertNotNull(decoded);
            actual.append(UTF_8.decode(decoded.getByteBuffer()));
            decoded.release();

            assertEquals(data, actual.toString());
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
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
            actual.append(UTF_8.decode(decoded.getByteBuffer()));
            decoded.release();

            decoded = decoder.decode(slice2);
            assertNotNull(decoded);
            actual.append(UTF_8.decode(decoded.getByteBuffer()));
            decoded.release();

            assertEquals(data, actual.toString());
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
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
            StringBuilder result = new StringBuilder();
            while (bytes.hasRemaining())
            {
                RetainableByteBuffer part = decoder.decode(bytes);
                result.append(UTF_8.decode(part.getByteBuffer()));
                part.release();
            }
            decoder.finishInput();
            assertEquals(data1 + data2, result.toString());
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
        }
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
