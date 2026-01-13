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
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ZstandardEncoderSinkTest extends AbstractZstdTest
{
    /**
     * Test that frequent flushing causes byte duplication at the 128KB boundary with small buffer.
     */
    @Test
    public void testFrequentFlushingAtBoundarySmallBuffer() throws Exception
    {
        startZstd();

        // Build expected content: 21847 lines of "XXXXX\n" (6 bytes each)
        // 21845 * 6 = 131070 bytes, which is just under 128KB boundary
        StringBuilder expected = new StringBuilder();
        for (int i = 1; i <= 21847; i++)
        {
            expected.append(String.format("%05d\n", i));
        }

        byte[] compressed;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            // Use default config (8KB buffer)
            Content.Sink encoderSink = zstd.newEncoderSink(fileSink);

            // Write each line separately (frequent flushing)
            for (int i = 1; i <= 21847; i++)
            {
                String line = String.format("%05d\n", i);
                boolean isLast = (i == 21847);
                Callback.Completable callback = new Callback.Completable();
                encoderSink.write(isLast, ByteBuffer.wrap(line.getBytes(UTF_8)), callback);
                callback.get();
            }
            compressed = baos.toByteArray();
        }

        // Decompress and verify - should match exactly
        String decompressed = new String(decompress(compressed), UTF_8);
        assertEquals(expected.toString(), decompressed);
    }

    /**
     * Test that frequent flushing works correctly with recommended 132KB buffer.
     * Note: This test uses data that fits entirely in 132KB buffer, so it may
     * pass simply by avoiding multiple iterations through the encoder loop.
     */
    @Test
    public void testFrequentFlushingAtBoundaryLargeBuffer() throws Exception
    {
        startZstd();

        // Build expected content: 21847 lines of "XXXXX\n" (6 bytes each)
        // 21845 * 6 = 131070 bytes, which is just under 128KB boundary
        StringBuilder expected = new StringBuilder();
        for (int i = 1; i <= 21847; i++)
        {
            expected.append(String.format("%05d\n", i));
        }

        byte[] compressed;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            // Use larger buffer (132KB as recommended by zstd)
            ZstandardEncoderConfig config = new ZstandardEncoderConfig();
            config.setBufferSize(132 * 1024);
            Content.Sink encoderSink = zstd.newEncoderSink(fileSink, config);

            // Write each line separately (frequent flushing)
            for (int i = 1; i <= 21847; i++)
            {
                String line = String.format("%05d\n", i);
                boolean isLast = (i == 21847);
                Callback.Completable callback = new Callback.Completable();
                encoderSink.write(isLast, ByteBuffer.wrap(line.getBytes(UTF_8)), callback);
                callback.get();
            }
            compressed = baos.toByteArray();
        }

        // Decompress and verify - should match exactly
        String decompressed = new String(decompress(compressed), UTF_8);
        assertEquals(expected.toString(), decompressed);
    }

    /**
     * Test with data exceeding 132KB to force multiple iterations even with large buffer.
     * 50000 lines * 6 bytes = 300KB, requiring multiple buffer fills even with 132KB buffer.
     */
    @Test
    public void testFrequentFlushingLargeBufferMultipleIterations() throws Exception
    {
        startZstd();

        // Build expected content: 50000 lines of "XXXXX\n" (6 bytes each) = 300KB
        // This exceeds 132KB buffer, forcing multiple iterations
        StringBuilder expected = new StringBuilder();
        for (int i = 1; i <= 50000; i++)
        {
            expected.append(String.format("%05d\n", i));
        }

        byte[] compressed;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            // Use larger buffer (132KB as recommended by zstd)
            ZstandardEncoderConfig config = new ZstandardEncoderConfig();
            config.setBufferSize(132 * 1024);
            Content.Sink encoderSink = zstd.newEncoderSink(fileSink, config);

            // Write each line separately (frequent flushing)
            for (int i = 1; i <= 50000; i++)
            {
                String line = String.format("%05d\n", i);
                boolean isLast = (i == 50000);
                Callback.Completable callback = new Callback.Completable();
                encoderSink.write(isLast, ByteBuffer.wrap(line.getBytes(UTF_8)), callback);
                callback.get();
            }
            compressed = baos.toByteArray();
        }

        // Decompress and verify - should match exactly
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
}
