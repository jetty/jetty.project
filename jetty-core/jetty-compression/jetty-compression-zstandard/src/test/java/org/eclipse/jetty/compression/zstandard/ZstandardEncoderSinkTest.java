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

package org.eclipse.jetty.compression.zstandard;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ZstandardEncoderSinkTest extends AbstractZstdTest
{
    public static Stream<Arguments> frequentFlushingCases()
    {
        return Stream.of(
            // lineCount, bufferSize (-1 means default), useDirect
            Arguments.of(21847, -1, false),
            Arguments.of(21847, 132 * 1024, false),
            Arguments.of(50000, 132 * 1024, false),
            Arguments.of(21847, 132 * 1024, true)
        );
    }

    @ParameterizedTest
    @MethodSource("frequentFlushingCases")
    public void testFrequentFlushing(int lineCount, int bufferSize, boolean useDirect) throws Exception
    {
        startZstd();

        StringBuilder expected = new StringBuilder();
        for (int i = 1; i <= lineCount; i++)
        {
            expected.append(String.format("%05d\n", i));
        }

        byte[] compressed;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink encoderSink;
            if (bufferSize > 0)
            {
                ZstandardEncoderConfig config = new ZstandardEncoderConfig();
                config.setBufferSize(bufferSize);
                encoderSink = zstd.newEncoderSink(fileSink, config);
            }
            else
            {
                encoderSink = zstd.newEncoderSink(fileSink);
            }

            for (int i = 1; i <= lineCount; i++)
            {
                String line = String.format("%05d\n", i);
                byte[] lineBytes = line.getBytes(UTF_8);
                ByteBuffer buffer = useDirect ? ByteBuffer.allocateDirect(lineBytes.length) : ByteBuffer.allocate(lineBytes.length);
                buffer.put(lineBytes);
                buffer.flip();

                boolean isLast = (i == lineCount);
                Callback.Completable callback = new Callback.Completable();
                encoderSink.write(isLast, buffer, callback);
                callback.get();
            }
            compressed = baos.toByteArray();
        }

        String decompressed = new String(decompress(compressed), UTF_8);
        assertEquals(expected.toString(), decompressed);
    }

    @ParameterizedTest
    @MethodSource("textResources")
    public void testEncodeText(String textResourceName) throws Exception
    {
        startZstd();
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);
        byte[] compressed = null;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink encoderSink = zstd.newEncoderSink(fileSink);

            Content.Source fileSource = Content.Source.from(sizedPool, uncompressed);
            Callback.Completable callback = new Callback.Completable();
            Content.copy(fileSource, encoderSink, callback);
            callback.get();
            compressed = baos.toByteArray();
        }

        // Verify contents
        String decompressed = new String(decompress(compressed), StandardCharsets.UTF_8);
        String expected = Files.readString(uncompressed, StandardCharsets.UTF_8);
        assertEquals(expected, decompressed);
    }

    @Test
    public void testWriteLastTwice() throws Exception
    {
        startZstd();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink encoderSink = zstd.newEncoderSink(fileSink);

            Callback.Completable callback1 = new Callback.Completable();
            encoderSink.write(true, ByteBuffer.wrap("Hello World!".getBytes(UTF_8)), callback1);
            callback1.get();
            assertThat(new String(decompress(baos.toByteArray()), UTF_8), is("Hello World!"));

            Callback.Completable callback2 = new Callback.Completable();
            encoderSink.write(true, ByteBuffer.wrap("Hello again!".getBytes(UTF_8)), callback2);
            ExecutionException thrown = assertThrows(ExecutionException.class, callback2::get);
            assertInstanceOf(IllegalStateException.class, thrown.getCause());
        }
    }

    public static Stream<Arguments> directBufferCases()
    {
        return Stream.of(
            // dataSize, bufferSize, startOffset
            Arguments.of(1024, 64 * 1024, 0),         // Small data, position 0
            Arguments.of(1024, 64 * 1024, 512),       // Small data, non-zero position
            Arguments.of(200 * 1024, 64 * 1024, 0),   // Large data exceeding bufferSize, position 0
            Arguments.of(200 * 1024, 64 * 1024, 100), // Large data exceeding bufferSize, non-zero position
            Arguments.of(21847 * 6, 132 * 1024, 0)    // Match frequentFlushing test size with direct buffer
        );
    }

    @ParameterizedTest
    @MethodSource("directBufferCases")
    public void testDirectBuffer(int dataSize, int bufferSize, int startOffset) throws Exception
    {
        startZstd();

        byte[] originalData = new byte[dataSize];
        new Random(42).nextBytes(originalData);

        ByteBuffer directBuffer = ByteBuffer.allocateDirect(dataSize + startOffset);
        for (int i = 0; i < startOffset; i++)
            directBuffer.put((byte)0xFF);
        directBuffer.put(originalData);
        directBuffer.position(startOffset);
        directBuffer.limit(startOffset + dataSize);

        byte[] compressed;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            ZstandardEncoderConfig config = new ZstandardEncoderConfig();
            config.setBufferSize(bufferSize);
            Content.Sink encoderSink = zstd.newEncoderSink(fileSink, config);

            Callback.Completable callback = new Callback.Completable();
            encoderSink.write(true, directBuffer, callback);
            callback.get();
            compressed = baos.toByteArray();
        }

        assertThat(directBuffer.remaining(), is(0));

        byte[] decompressed = decompress(compressed);
        assertThat(decompressed.length, is(originalData.length));
        for (int i = 0; i < originalData.length; i++)
        {
            assertThat("Mismatch at byte " + i, decompressed[i], is(originalData[i]));
        }
    }
}
