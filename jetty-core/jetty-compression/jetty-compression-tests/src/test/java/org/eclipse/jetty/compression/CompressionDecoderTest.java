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
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class CompressionDecoderTest extends AbstractCompressionTest
{
    @ParameterizedTest
    @MethodSource("compressions")
    public void testBigBlock(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0123456789ABCDEF".repeat(10);
        ByteBuffer bytes = asDirect(compress(data));

        try (Compression.Decoder decoder = compression.newDecoder())
        {
            StringBuilder actual = new StringBuilder();

            RetainableByteBuffer decoded = decoder.decode(bytes);
            actual.append(UTF_8.decode(decoded.getByteBuffer()));
            decoded.release();
            decoder.finishInput();

            assertEquals(data, actual.toString());
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testBigBlockOneByteAtATime(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        String data = "0123456789ABCDEF".repeat(10);
        byte[] compressedBytes = compress(data);

        // Using compression specific inputstream features.
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             InputStream input = compression.newEncoderInputStream(bytesIn))
        {
            int read;
            while ((read = input.read()) >= 0)
            {
                bytesOut.write(read);
            }
            assertEquals(data, bytesOut.toString(UTF_8));
        }

        // Using GzipDecoder.
        try (Compression.Decoder decoder = compression.newDecoder())
        {
            StringBuilder actualContent = new StringBuilder();
            ByteBuffer compressed = asDirect(compressedBytes);

            while (compressed.hasRemaining())
            {
                // decode 1 byte at a time for this test
                ByteBuffer singleByte = asDirect(new byte[]{compressed.get()});
                RetainableByteBuffer output = decoder.decode(singleByte);
                assertThat(output, notNullValue());
                if (output.hasRemaining())
                {
                    actualContent.append(UTF_8.decode(output.getByteBuffer()));
                }
                output.release();
            }
            decoder.finishInput();

            assertEquals(data, actualContent.toString());
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testBigBlockWithExtraBytes(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass, 64);
        String data1 = "0123456789ABCDEF".repeat(10);
        ByteBuffer bytes1 = ByteBuffer.wrap(compress(data1));
        String data2 = "HELLO";
        ByteBuffer bytes2 = ByteBuffer.wrap(data2.getBytes(UTF_8));

        ByteBuffer bytes = ByteBuffer.allocateDirect(bytes1.remaining() + bytes2.remaining());
        bytes.put(bytes1.slice());
        bytes.put(bytes2.slice());
        BufferUtil.flipToFlush(bytes, 0);

        try (Compression.Decoder decoder = compression.newDecoder())
        {
            StringBuilder actual = new StringBuilder();

            try
            {
                while (bytes.hasRemaining())
                {
                    RetainableByteBuffer decoded = decoder.decode(bytes);
                    actual.append(UTF_8.decode(decoded.getByteBuffer()));
                    decoded.release();
                }
                decoder.finishInput();
            }
            catch (IOException e)
            {
                // some implementations will reject decoding when it
                // encounters extra invalid bytes.
                if (e.getMessage().startsWith("Decoder failure"))
                    Assumptions.abort("Implementation does not support extra invalid bytes");
                throw e;
            }
            assertTrue(decoder.isOutputComplete(), "Output has been completed");

            assertEquals(data1, actual.toString());
            // The extra bytes should not fail the decoder.
            // Some implementations will not read the extra bytes,
            // other implementations will read and discard these extra bytes.
            assertThat(actual.toString(), not(containsString(data2)));
        }
    }

    @ParameterizedTest
    @MethodSource("textInputs")
    public void testDecodeText(Class<Compression> compressionClass, String uncompressedRef) throws Exception
    {
        startCompression(compressionClass);
        Path uncompressedPath = MavenPaths.findTestResourceFile(uncompressedRef);
        String compressionExtension = compression.getFileExtensionNames().stream().sorted().findFirst().orElseThrow();
        Path compressedPath = MavenPaths.findTestResourceFile(uncompressedRef + "." + compressionExtension);

        StringBuilder result = new StringBuilder();

        try (Compression.Decoder decoder = compression.newDecoder();
             SeekableByteChannel inputChannel = Files.newByteChannel(compressedPath, StandardOpenOption.READ))
        {
            RetainableByteBuffer readRetainableBuffer = compression.acquireByteBuffer(2048);
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
                            if (len > 0)
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

        String expected = Files.readString(uncompressedPath);
        assertEquals(expected, result.toString());
    }

    /**
     * Decode where no bytes were given to it, and then it is finished.
     * This should trigger an IOException about the decoding failure.
     */
    @ParameterizedTest
    @MethodSource("compressions")
    public void testDecodeEmpty(Class<Compression> compressionClass) throws Exception
    {
        ByteBuffer emptyDirect = ByteBuffer.allocateDirect(0);
        startCompression(compressionClass);
        try (Compression.Decoder decoder = compression.newDecoder())
        {
            RetainableByteBuffer buf = decoder.decode(emptyDirect);
            assertThat(buf, is(notNullValue()));
            assertThat(buf.getByteBuffer().hasRemaining(), is(false));
            decoder.finishInput();
            buf.release();
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testNoBlocks(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        byte[] compressedBytes = compress(null);

        // Compressed bytes should have some content, and not be totally blank.
        assertThat(compressedBytes.length, greaterThan(0));

        // Using compression specific inputstream, this should result in an immediate EOF (read == -1).
        try (ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             InputStream input = compression.newEncoderInputStream(bytesIn))
        {
            int read = input.read();
            assertEquals(-1, read, "Expected EOF");
        }

        // Using Decoder this should result in no content
        try (Compression.Decoder decoder = compression.newDecoder())
        {
            ByteBuffer compressed = asDirect(compressedBytes);
            RetainableByteBuffer output = decoder.decode(compressed);
            assertThat(output, notNullValue());
            assertThat(output.getByteBuffer().remaining(), is(0));
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
            output.release();
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testOneEmptyBlock(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        byte[] compressedBytes = compress("");

        // Compressed bytes should have some content, and not be totally blank.
        assertThat(compressedBytes.length, greaterThan(0));

        // Using compression specific inputstream, this should result in an immediate EOF (read == -1).
        try (ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             InputStream input = compression.newEncoderInputStream(bytesIn))
        {
            int read = input.read();
            assertEquals(-1, read, "Expected EOF");
        }

        // Using Decoder this should result in no content
        try (Compression.Decoder decoder = compression.newDecoder())
        {
            ByteBuffer compressed = asDirect(compressedBytes);
            RetainableByteBuffer output = decoder.decode(compressed);
            assertThat(output, notNullValue());
            assertThat(output.getByteBuffer().remaining(), is(0));
            output.release();
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testSmallBlock(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0";
        byte[] compressedBytes = compress(data);

        try (Compression.Decoder decoder = compression.newDecoder())
        {
            ByteBuffer compressed = asDirect(compressedBytes);
            RetainableByteBuffer decoded = decoder.decode(compressed);
            assertEquals(data, UTF_8.decode(decoded.getByteBuffer()).toString());
            decoded.release();
            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testSmallBlockChunkedAtEnd(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0";
        ByteBuffer bytes = asDirect(compress(data));

        // Split at the end of the block leaving the second block at 1 byte.
        int split = bytes.remaining() - 1;
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        try (Compression.Decoder decoder = compression.newDecoder())
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

            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");

            assertEquals(data, actual.toString());
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testSmallBlockChunkedAtStart(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0";
        ByteBuffer bytes = asDirect(compress(data));

        // Split at the start of the block leaving the first block at 1 byte.
        int split = 1;
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        try (Compression.Decoder decoder = compression.newDecoder())
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

            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");

            assertEquals(data, actual.toString());
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testSmallBlockChunkedInHalf(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0";
        ByteBuffer bytes = asDirect(compress(data));

        // Split in half (roughly)
        int split = (int)(double)(bytes.remaining() / 2);
        ByteBuffer slice1 = bytes.slice();
        slice1.limit(split);
        ByteBuffer slice2 = bytes.slice();
        slice2.position(split);

        try (Compression.Decoder decoder = compression.newDecoder())
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

            decoder.finishInput();
            assertTrue(decoder.isOutputComplete(), "Output has been completed");

            assertEquals(data, actual.toString());
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testSmallBlockOneByteAtATime(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        String data = "0123456789ABCDEF";
        byte[] compressedBytes = compress(data);

        // Using compression specific inputstream, produce output as if 1 byte at a time was written
        try (ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
             ByteArrayInputStream bytesIn = new ByteArrayInputStream(compressedBytes);
             InputStream input = compression.newEncoderInputStream(bytesIn))
        {
            int read;
            while ((read = input.read()) >= 0)
            {
                bytesOut.write(read);
            }
            assertEquals(data, bytesOut.toString(UTF_8));
        }

        // Using Decoder
        try (Compression.Decoder decoder = compression.newDecoder())
        {
            StringBuilder actualContent = new StringBuilder();
            ByteBuffer compressed = asDirect(compressedBytes);

            while (compressed.hasRemaining())
            {
                // decode 1 byte at a time for this test
                ByteBuffer singleByte = asDirect(new byte[]{compressed.get()});
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

    @ParameterizedTest
    @MethodSource("compressions")
    public void testTwoSmallBlocks(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data1 = "0";
        // Entire compressed block (headers + content + trailers)
        ByteBuffer bytes1 = ByteBuffer.wrap(compress(data1));
        String data2 = "1";
        // Yet another entire compressed block (headers + content + trailers)
        ByteBuffer bytes2 = ByteBuffer.wrap(compress(data2));

        // Buffer containing 2 entire compressed blocks.
        ByteBuffer bytes = ByteBuffer.allocateDirect(bytes1.remaining() + bytes2.remaining());

        bytes.put(bytes1.slice());
        bytes.put(bytes2.slice());

        BufferUtil.flipToFlush(bytes, 0);

        try (Compression.Decoder decoder = compression.newDecoder())
        {
            StringBuilder result = new StringBuilder();
            while (bytes.hasRemaining())
            {
                RetainableByteBuffer part = decoder.decode(bytes);
                result.append(UTF_8.decode(part.getByteBuffer()));
                part.release();
            }
            try
            {
                decoder.finishInput();
            }
            catch (IOException e)
            {
                // some implementations do not support sequential blocks (eg: brotli)
                if (e.getMessage().startsWith("Decoder failure"))
                    Assumptions.abort("Implementation does not support extra invalid bytes");
                throw e;
            }
            decoder.finishInput();
            assertEquals(data1 + data2, result.toString());
            assertTrue(decoder.isOutputComplete(), "Output has been completed");
        }
    }
}
