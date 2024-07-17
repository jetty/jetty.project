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
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliDecoderChannel;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.CompletableTask;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BrotliDecoderTest extends AbstractBrotliTest
{
    @Test
    public void testBrotliDecodeEmpty() throws Exception
    {
        startBrotli();
        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();

        RetainableByteBuffer buf = decoder.decode(BufferUtil.EMPTY_BUFFER);
        assertFalse(buf.hasRemaining());
    }

    @Test
    public void testDecodeEmpty() throws Exception
    {
        startBrotli();
        Compression.Decoder decoder = brotli.newDecoder();

        RetainableByteBuffer buf = decoder.decode(Content.Chunk.EMPTY);
        assertFalse(buf.hasRemaining());
    }

    public static Stream<Arguments> precompressedText()
    {
        return Stream.of(
            Arguments.of("precompressed/test_quotes.txt.br", "precompressed/test_quotes.txt")
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
        startBrotli();
        Compression.Decoder decoder = brotli.newDecoder();
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

    @Test
    public void testStripSuffixes()
    {
        BrotliCompression brotli = new BrotliCompression();
        assertThat(brotli.stripSuffixes("12345"), is("12345"));
        assertThat(brotli.stripSuffixes("12345, 666" + brotli.getEtagSuffix()), is("12345, 666"));
        assertThat(brotli.stripSuffixes("12345, 666" + brotli.getEtagSuffix() + ",W/\"9999" + brotli.getEtagSuffix() + "\""),
            is("12345, 666,W/\"9999\""));
    }

    @Test
    public void testStreamNoBlocks() throws Exception
    {
        Brotli4jLoader.ensureAvailability();

        byte[] bytes;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.close();
            bytes = baos.toByteArray();
        }

        try (BrotliInputStream input = new BrotliInputStream(new ByteArrayInputStream(bytes), 1))
        {
            int read = input.read();
            assertEquals(-1, read);
        }
    }

    @Test
    public void testStreamBigBlockOneByteAtATime() throws Exception
    {
        Brotli4jLoader.ensureAvailability();

        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }

        byte[] bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes = baos.toByteArray();
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliInputStream input = new BrotliInputStream(new ByteArrayInputStream(bytes), 1))
        {
            int read;
            while ((read = input.read()) >= 0)
            {
                baos.write(read);
            }
            assertEquals(data, baos.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void testNoBlocks() throws Exception
    {
        startBrotli();

        ByteBuffer bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.close();
            bytes = ByteBuffer.wrap(baos.toByteArray());
        }

        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(Content.Chunk.from(bytes, true));
        assertEquals(0, decoded.remaining());
        decoded.release();
    }

    @Test
    public void testSmallBlock() throws Exception
    {
        startBrotli();
        String data = "0";

        ByteBuffer bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes = ByteBuffer.wrap(baos.toByteArray());
        }

        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(bytes);
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        decoded.release();
    }

    @Test
    public void testSmallBlockChunked() throws Exception
    {
        startBrotli();
        String data = "0";

        ByteBuffer bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes = ByteBuffer.wrap(baos.toByteArray());
        }

        // Split roughly at halfway mark
        int split = (int)(double)(bytes.remaining() / 2);
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(slice1);
        assertThat(decoded.remaining(), is(0));
        decoded.release();
        decoded = decoder.decode(slice2);
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        decoded.release();
    }

    @Test
    public void testSmallBlockWithOneByteEndChunk() throws Exception
    {
        startBrotli();

        String data = "0";
        ByteBuffer bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes = ByteBuffer.wrap(baos.toByteArray());
        }

        // Last slice should be 1 byte.
        int split = bytes.remaining() - 1;
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        RetainableByteBuffer decoded = decoder.decode(slice1);
        assertThat(decoded.remaining(), is(0));
        decoded.release();
        decoded = decoder.decode(slice2);
        assertEquals(data, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());
        decoded.release();
    }

    @Test
    @Disabled("Not supported by brotli4j")
    public void testTwoSmallBlocks() throws Exception
    {
        startBrotli();

        String data1 = "0";
        ByteBuffer bytes1;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data1.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes1 = ByteBuffer.wrap(baos.toByteArray());
        }

        String data2 = "1";
        ByteBuffer bytes2;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data2.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes2 = ByteBuffer.wrap(baos.toByteArray());
        }

        ByteBuffer bytes = ByteBuffer.allocate(bytes1.remaining() + bytes2.remaining());

        bytes.put(bytes1.slice());
        bytes.put(bytes2.slice());

        BufferUtil.flipToFlush(bytes, 0);

        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();

        RetainableByteBuffer.DynamicCapacity decoded = new RetainableByteBuffer.DynamicCapacity();
        RetainableByteBuffer part;

        part = decoder.decode(bytes);
        decoded.append(part);
        part.release();

        part = decoder.decode(bytes);
        decoded.append(part);
        part.release();

        assertEquals(data1 + data2, StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString());

        decoded.release();
    }

    @Test
    public void testBigBlock() throws Exception
    {
        startBrotli();
        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }

        ByteBuffer bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes = ByteBuffer.wrap(baos.toByteArray());
        }

        String result = "";
        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        while (bytes.hasRemaining())
        {
            RetainableByteBuffer decoded = decoder.decode(bytes);
            result += StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString();
            decoded.release();
        }
        assertEquals(data, result);
    }

    @Test
    public void testSmallBlockOneByteAtATime() throws Exception
    {
        startBrotli(256);
        String data = "0123456789ABCDEF";

        ByteBuffer bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes = ByteBuffer.wrap(baos.toByteArray());
        }

        String result = "";
        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        while (bytes.hasRemaining())
        {
            ByteBuffer singleByte = ByteBuffer.wrap(new byte[]{bytes.get()});
            RetainableByteBuffer decoded = decoder.decode(singleByte);
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString();
            decoded.release();
        }
        assertEquals(data, result);
        assertTrue(decoder.isFinished());
    }

    @Test
    public void testSmallBlockOneByteAtTimeChannel() throws Exception
    {
        Brotli4jLoader.ensureAvailability();

        String data = "0123456789ABCDEF";
        ByteBuffer bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes = ByteBuffer.wrap(baos.toByteArray());
        }

        ReadableByteChannel oneByteAtATimeChannel = new OneByteAtATimeByteChannel(bytes);
        BrotliDecoderChannel channel = new BrotliDecoderChannel(oneByteAtATimeChannel);
        ByteBuffer output = ByteBuffer.allocate(2048);
        channel.read(output);
        output.flip();
        String result = StandardCharsets.UTF_8.decode(output).toString();
        assertEquals("0123456789ABCDEF", result);
    }

    @Test
    public void testBigBlockOneByteAtATime() throws Exception
    {
        startBrotli(64);
        String data = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
        {
            data += data;
        }

        ByteBuffer bytes;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes = ByteBuffer.wrap(baos.toByteArray());
        }

        String result = "";
        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        while (bytes.hasRemaining())
        {
            ByteBuffer singleByte = ByteBuffer.wrap(new byte[]{bytes.get()});
            RetainableByteBuffer decoded = decoder.decode(singleByte);
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString();
            decoded.release();
        }
        assertEquals(data.length(), result.length());
        assertEquals(data, result);
        assertTrue(decoder.isFinished());
    }

    @Test
    public void testBigBlockWithExtraBytes() throws Exception
    {
        startBrotli(64);

        String data1 = "0123456789ABCDEF";
        for (int i = 0; i < 10; ++i)
        {
            data1 += data1;
        }

        ByteBuffer bytes1;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             BrotliOutputStream output = new BrotliOutputStream(baos))
        {
            output.write(data1.getBytes(StandardCharsets.UTF_8));
            output.close();
            bytes1 = ByteBuffer.wrap(baos.toByteArray());
        }

        String helloData = "HELLO";
        byte[] helloBytes = helloData.getBytes(StandardCharsets.UTF_8);

        ByteBuffer bytes = ByteBuffer.allocate(bytes1.remaining() + helloBytes.length);
        bytes.put(bytes1.slice());
        bytes.put(helloBytes);
        BufferUtil.flipToFlush(bytes, 0);

        String result = "";
        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        while (bytes.hasRemaining())
        {
            RetainableByteBuffer decoded = decoder.decode(bytes);
            if (decoded.hasRemaining())
                result += StandardCharsets.UTF_8.decode(decoded.getByteBuffer()).toString();
            decoded.release();
            if (decoder.isFinished())
                break;
        }
        assertEquals(data1, result);
        // brotli4j consumes the entire input buffer always.
        // even if there is extra content outside of the brotli data stream.
        assertFalse(bytes.hasRemaining());
    }

    // Signed Integer Max
    static final long INT_MAX = Integer.MAX_VALUE;

    // Unsigned Integer Max == 2^32
    static final long UINT_MAX = 0xFFFFFFFFL;

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
    public void testLargeBrotliStream(long origSize) throws Exception
    {
        final int BUFSIZE = 64 * 1024 * 1024;
        startBrotli(BUFSIZE);

        // Create a buffer to use over and over again to produce the uncompressed input
        byte[] chars = "0123456789ABCDEFGHIJKLMOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        byte[] buf = new byte[BUFSIZE];
        for (int off = 0; off < buf.length; )
        {
            int len = Math.min(chars.length, buf.length - off);
            System.arraycopy(chars, 0, buf, off, len);
            off += len;
        }

        Encoder.Parameters encoderParams = new Encoder.Parameters();
        BrotliDecoder decoder = (BrotliDecoder)brotli.newDecoder();
        try (BrotliDecoderOutputStream out = new BrotliDecoderOutputStream(decoder);
             BrotliOutputStream outputStream = new BrotliOutputStream(out, encoderParams, BUFSIZE))
        {
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

            // Close BrotliOutputStream to have it generate brotli trailer.
            // This can cause more writes of unflushed brotli buffers
            outputStream.close();

            // out.decodedByteCount is only valid after close
            assertThat("Decoded byte count", out.decodedByteCount, is(origSize));
        }
    }

    public static class BrotliDecoderOutputStream extends OutputStream
    {
        private final BrotliDecoder decoder;
        public long decodedByteCount = 0L;

        public BrotliDecoderOutputStream(BrotliDecoder decoder)
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

    private static class OneByteAtATimeByteChannel implements ReadableByteChannel
    {
        private final ByteBuffer buffer;

        public OneByteAtATimeByteChannel(ByteBuffer buffer)
        {
            this.buffer = buffer.slice();
        }

        @Override
        public boolean isOpen()
        {
            return true;
        }

        @Override
        public void close()
        {
        }

        @Override
        public int read(ByteBuffer dst)
        {
            if (!buffer.hasRemaining())
                return -1;
            dst.put(buffer.get());
            return 1;
        }
    }
}
