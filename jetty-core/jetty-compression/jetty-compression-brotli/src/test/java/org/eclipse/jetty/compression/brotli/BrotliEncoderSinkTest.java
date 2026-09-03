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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BrotliEncoderSinkTest extends AbstractBrotliTest
{
    @ParameterizedTest
    @MethodSource("textResources")
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    public void testEncodeText(String textResourceName) throws Exception
    {
        startBrotli();
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);
        byte[] compressed = null;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink encoderSink = brotli.newEncoderSink(fileSink);

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
        startBrotli();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink encoderSink = brotli.newEncoderSink(fileSink);

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

    /**
     * Regression test for large-response truncation: a single Brotli operation can emit more than one
     * output buffer, and every one of them must be written downstream. Incompressible (random) data and
     * a large encoder buffer force a single {@code PROCESS}/{@code FINISH} to produce multiple
     * {@code pull()} results; if any but the last were dropped the round-trip would not match.
     */
    @Test
    public void testLargeContentIsNotTruncated() throws Exception
    {
        startBrotli();

        int dataSize = 4 * 1024 * 1024;
        byte[] original = new byte[dataSize];
        new java.util.Random(42).nextBytes(original);

        BrotliEncoderConfig config = new BrotliEncoderConfig();
        config.setBufferSize(1024 * 1024);
        config.setCompressionLevel(5);

        byte[] compressed;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink encoderSink = brotli.newEncoderSink(fileSink, config);

            Callback.Completable callback = new Callback.Completable();
            encoderSink.write(true, ByteBuffer.wrap(original), callback);
            callback.get();
            compressed = baos.toByteArray();
        }

        byte[] decompressed = decompress(compressed);
        assertEquals(original.length, decompressed.length, "decompressed length (a shorter value means the response was truncated)");
        assertArrayEquals(original, decompressed);
    }
}
