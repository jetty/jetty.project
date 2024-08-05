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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

public class BrotliDecoderTest extends AbstractBrotliTest
{
    @Test
    public void testBigBlock() throws Exception
    {
        startBrotli();
        String data = "0123456789ABCDEF".repeat(10);
        ByteBuffer bytes = ByteBuffer.wrap(compress(data));

        try (Compression.Decoder decoder = brotli.newDecoder())
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
        startBrotli(64);
        String data = "0123456789ABCDEF".repeat(10);
        byte[] compressedBytes = compress(data);

        // Using Brotli4j BrotliInputStream
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             BrotliInputStream input = new BrotliInputStream(bytesIn, compressedBytes.length))
        {
            int read;
            while ((read = input.read()) >= 0)
            {
                bytesOut.write(read);
            }
            assertEquals(data, bytesOut.toString(UTF_8));
        }

        // Using BrotliDecoder.
        try (Compression.Decoder decoder = brotli.newDecoder())
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
        startBrotli(64);

        String data1 = "0123456789ABCDEF".repeat(10);
        ByteBuffer bytes1 = ByteBuffer.wrap(compress(data1));
        String data2 = "HELLO";
        ByteBuffer bytes2 = ByteBuffer.wrap(data2.getBytes(UTF_8));

        ByteBuffer bytes = ByteBuffer.allocate(bytes1.remaining() + bytes2.remaining());
        bytes.put(bytes1.slice());
        bytes.put(bytes2.slice());
        BufferUtil.flipToFlush(bytes, 0);

        try (Compression.Decoder decoder = brotli.newDecoder())
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

            // brotli4j consumes the entire input buffer always.
            // even if there is extra content outside of the brotli data stream.
            assertFalse(bytes.hasRemaining());
        }
    }

    @Test
    public void testDecodeEmpty() throws Exception
    {
        startBrotli();
        try (Compression.Decoder decoder = brotli.newDecoder())
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
       precompressed/test_quotes.txt.br | precompressed/test_quotes.txt
       precompressed/logo.svg.br        | precompressed/logo.svg
       precompressed/text-long.txt.br   | precompressed/text-long.txt
       """)
    public void testDecodeText(String precompressedRef, String expectedRef) throws Exception
    {
        startBrotli();
        Path inputPath = MavenPaths.findTestResourceFile(precompressedRef);

        StringBuilder result = new StringBuilder();

        try (Compression.Decoder decoder = brotli.newDecoder();
             SeekableByteChannel inputChannel = Files.newByteChannel(inputPath, StandardOpenOption.READ))
        {
            RetainableByteBuffer readRetainableBuffer = brotli.acquireByteBuffer(2048);
            try
            {
                ByteBuffer readBuffer = readRetainableBuffer.getByteBuffer();

                boolean readDone = false;
                while (!readDone && !decoder.isFinished())
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
                                break;
                            }
                            if (len > 0)
                            {
                                readBuffer.flip();
                                while (readBuffer.hasRemaining())
                                {
                                    RetainableByteBuffer decoded = decoder.decode(readBuffer);
                                    if (decoded.hasRemaining())
                                        result.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
                                    decoded.release();
                                }
                            }
                        }
                        else
                        {
                            // decode any remaining bytes
                            while (!decoder.isFinished())
                            {
                                RetainableByteBuffer decoded = decoder.decode(BufferUtil.EMPTY_BUFFER);
                                result.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
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
    public void testLargeBrotliStream(long origSize) throws Exception
    {
        startBrotli();

        // Create a buffer to use over and over again to produce the uncompressed input
        byte[] buf = "0123456789ABCDEFGHIJKLMOPQRSTUVWXYZ".repeat(100).getBytes(StandardCharsets.UTF_8);

        Encoder.Parameters encoderParams = new Encoder.Parameters();
        try (Compression.Decoder decoder = brotli.newDecoder();
             BrotliDecoderOutputStream out = new BrotliDecoderOutputStream(decoder);
             BrotliOutputStream outputStream = new BrotliOutputStream(out, encoderParams))
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

            // Close BrotliOutputStream to have it generate brotli trailer.
            // This can cause more writes of unflushed brotli buffers
            outputStream.close();

            // out.decodedByteCount is only valid after close
            assertThat("Decoded byte count", out.decodedByteCount, is(origSize));
        }
    }

    @Test
    public void testNoBlocks() throws Exception
    {
        startBrotli();

        byte[] compressedBytes = compress(null);

        // Compressed bytes should have some content, and not be totally blank.
        assertThat(compressedBytes.length, greaterThan(0));

        // Using Brotli4j features, this should result in an immediate EOF (read == -1).
        try (ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             BrotliInputStream input = new BrotliInputStream(bytesIn, compressedBytes.length))
        {
            int read = input.read();
            assertEquals(-1, read, "Expected EOF");
        }

        // Using BrotliDecoder this should result in no content
        try (Compression.Decoder decoder = brotli.newDecoder())
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
        startBrotli();
        byte[] compressedBytes = compress("");

        // Compressed bytes should have some content, and not be totally blank.
        assertThat(compressedBytes.length, greaterThan(0));

        // Using Brotli4j features, this should result in an immediate EOF (read == -1).
        try (ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             BrotliInputStream input = new BrotliInputStream(bytesIn, compressedBytes.length))
        {
            int read = input.read();
            assertEquals(-1, read, "Expected EOF");
        }

        // Using BrotliDecoder this should result in no content
        try (Compression.Decoder decoder = brotli.newDecoder())
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
        startBrotli();
        String data = "0";
        byte[] compressedBytes = compress(data);

        try (Compression.Decoder decoder = brotli.newDecoder())
        {
            ByteBuffer compressed = ByteBuffer.wrap(compressedBytes);
            RetainableByteBuffer decoded = decoder.decode(compressed);
            assertEquals(data, BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();
        }
    }

    @Test
    public void testSmallBlockChunked() throws Exception
    {
        startBrotli();
        String data = "Jetty";

        ByteBuffer bytes = ByteBuffer.wrap(compress(data));

        // We should have something we can split
        assertThat(bytes.remaining(), greaterThan(3));

        // Split roughly at halfway mark
        int split = (int)(double)(bytes.remaining() / 2);
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        try (Compression.Decoder decoder = brotli.newDecoder())
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
    public void testSmallBlockOneByteAtATime() throws Exception
    {
        startBrotli(256);

        String data = "0123456789ABCDEF";
        byte[] compressedBytes = compress(data);

        // Using Brotli features
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             BrotliInputStream input = new BrotliInputStream(bytesIn, compressedBytes.length))
        {
            int read;
            while ((read = input.read()) >= 0)
            {
                bytesOut.write(read);
            }
            assertEquals(data, bytesOut.toString(UTF_8));
        }

        // Using BrotliDecoder
        try (Compression.Decoder decoder = brotli.newDecoder())
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
    public void testSmallBlockWithOneByteEndChunk() throws Exception
    {
        startBrotli();

        String data = "Jetty";
        ByteBuffer bytes = ByteBuffer.wrap(compress(data));
        assertThat(bytes.remaining(), greaterThan(3));

        // Last slice should be 1 byte.
        int split = bytes.remaining() - 1;
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        try (Compression.Decoder decoder = brotli.newDecoder())
        {
            StringBuilder actualContent = new StringBuilder();
            RetainableByteBuffer decoded = decoder.decode(slice1);
            actualContent.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            decoded = decoder.decode(slice2);
            actualContent.append(BufferUtil.toString(decoded.getByteBuffer(), UTF_8));
            decoded.release();

            assertEquals(data, actualContent.toString());
        }
    }

    @Test
    public void testStripSuffixes()
    {
        BrotliCompression brotli = new BrotliCompression();
        assertThat(brotli.stripSuffixes("12345"), is("12345"));
        assertThat(brotli.stripSuffixes("12345, 666" + brotli.getEtagSuffix()), is("12345, 666"));
        assertThat(brotli.stripSuffixes("12345, 666" + brotli.getEtagSuffix() + ",W/\"9999" + brotli.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }

    public static class BrotliDecoderOutputStream extends OutputStream
    {
        private final Compression.Decoder decoder;
        public long decodedByteCount = 0L;

        public BrotliDecoderOutputStream(Compression.Decoder decoder)
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
