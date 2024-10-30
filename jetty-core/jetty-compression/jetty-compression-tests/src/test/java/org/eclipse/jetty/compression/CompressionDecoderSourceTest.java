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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompressionDecoderSourceTest extends AbstractCompressionTest
{
    @ParameterizedTest
    @MethodSource("compressions")
    public void testBigBlock(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0123456789ABCDEF".repeat(10);
        ByteBuffer bytes = asDirect(compress(data));

        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals(data, result);
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testBigBlockOnByteAtATime(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0123456789ABCDEF".repeat(10);
        ByteBuffer bytes = asDirect(compress(data));

        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source maxBufSizeSource = new MaxBufferContentSource(compressedSource, 1);
        Content.Source decoderSource = compression.newDecoderSource(maxBufSizeSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals(data, result);
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

        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        try
        {
            String result = Content.Source.asString(decoderSource);
            assertEquals(data1, result);
        }
        catch (IOException e)
        {
            // some implementations will reject decoding when it
            // encounters extra invalid bytes.
            if (e.getMessage().startsWith("Decoder failure"))
                Assumptions.abort("Implementation does not support extra invalid bytes");
        }
    }

    /**
     * Decode where only a single empty buffer is given to it.
     * This is expected to not trigger an IOException about any kind of decoding failure.
     */
    @ParameterizedTest
    @MethodSource("compressions")
    public void testDecodeEmpty(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        ByteBuffer bytes = ByteBuffer.allocateDirect(0);
        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals("", result);
    }

    /**
     * Decode where only a single no-bytes buffer is compressed.
     * This is expected to not trigger an IOException about any kind of decoding failure.
     */
    @ParameterizedTest
    @MethodSource("compressions")
    public void testDecodeEmptyBlock(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        ByteBuffer bytes = asDirect(compress(""));
        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals("", result);
    }

    /**
     * Decode where only a single empty buffer is compressed.
     * This is expected to not trigger an IOException about any kind of decoding failure.
     */
    @ParameterizedTest
    @MethodSource("compressions")
    public void testDecodeNoBlocks(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        ByteBuffer bytes = asDirect(compress((byte[])null));
        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals("", result);
    }

    @ParameterizedTest
    @MethodSource("textInputs")
    public void testDecodeText(Class<Compression> compressionClass, String textResourceName) throws Exception
    {
        startCompression(compressionClass);
        String compressedName = String.format("%s.%s", textResourceName, compression.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);

        ByteBufferPool.Sized sizedPool = new ByteBufferPool.Sized(pool, true, 4096);
        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source decoderSource = compression.newDecoderSource(fileSource);

        String result = Content.Source.asString(decoderSource);
        String expected = Files.readString(uncompressed);
        assertEquals(expected, result);
    }

    @ParameterizedTest
    @MethodSource("textInputs")
    // @Timeout(value = 5, unit = TimeUnit.SECONDS)
    public void testDecodeTextOneByteAtATime(Class<Compression> compressionClass, String textResourceName) throws Exception
    {
        startCompression(compressionClass);
        String compressedName = String.format("%s.%s", textResourceName, compression.getFileExtensionNames().get(0));
        Path compressed = MavenPaths.findTestResourceFile(compressedName);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);

        ByteBufferPool.Sized sizedPool = new ByteBufferPool.Sized(pool, true, 4096);
        Content.Source fileSource = Content.Source.from(sizedPool, compressed);
        Content.Source maxBufSizeSource = new MaxBufferContentSource(fileSource, 1);
        Content.Source decoderSource = compression.newDecoderSource(maxBufSizeSource);

        String result = Content.Source.asString(decoderSource);
        String expected = Files.readString(uncompressed);
        assertEquals(expected, result);
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testSmallBlock(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0";

        ByteBuffer bytes = asDirect(compress(data));
        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals(data, result);
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testSmallBlockChunked(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0";

        ByteBuffer bytes = asDirect(compress(data));
        Content.Source compressedSource = Content.Source.from(bytes);
        int halfish = (int)(double)(bytes.remaining() / 2);
        Content.Source maxBufSizeSource = new MaxBufferContentSource(compressedSource, halfish);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals(data, result);
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testSmallBlockOnByteAtATime(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);
        String data = "0";

        ByteBuffer bytes = asDirect(compress(data));
        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source maxBufSizeSource = new MaxBufferContentSource(compressedSource, 1);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals(data, result);
    }

    /**
     * Some compression libs can take multiple entire conversations and merge them
     * into a single response.  Eg: two entire gzip blocks (header + data + trailer).
     */
    @ParameterizedTest
    @MethodSource("compressions")
    @Disabled("Not supported in current Compression libs")
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

        Content.Source compressedSource = Content.Source.from(bytes);
        Content.Source decoderSource = compression.newDecoderSource(compressedSource);

        String result = Content.Source.asString(decoderSource);
        assertEquals(data1 + data2, result);
    }
}
