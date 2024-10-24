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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompressionEncoderSinkTest extends AbstractCompressionTest
{
    @ParameterizedTest
    @MethodSource("compressions")
    public void testEncodeEmptyBuffer(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        ByteBuffer input = ByteBuffer.allocateDirect(0);
        Content.Source uncompressedSource = Content.Source.from(input);

        ByteArrayOutputStream compressedCapture = new ByteArrayOutputStream();
        Content.Sink byteCaptureSink = Content.Sink.from(compressedCapture);
        Content.Sink encoderSink = compression.newEncoderSink(byteCaptureSink);

        Callback.Completable completable = new Callback.Completable();
        Content.copy(uncompressedSource, encoderSink, completable);
        completable.get();

        String decompressed = new String(decompress(compressedCapture.toByteArray()), UTF_8);
        assertEquals("", decompressed);
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testEncodeSingleBuffer(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        String inputString = "Hello World, this is " + CompressionEncoderSinkTest.class.getName();
        ByteBuffer input = asDirect(inputString);
        Content.Source uncompressedSource = Content.Source.from(input);

        ByteArrayOutputStream compressedCapture = new ByteArrayOutputStream();
        Content.Sink byteCaptureSink = Content.Sink.from(compressedCapture);
        Content.Sink encoderSink = compression.newEncoderSink(byteCaptureSink);

        Callback.Completable completable = new Callback.Completable();
        Content.copy(uncompressedSource, encoderSink, completable);
        completable.get();

        String decompressed = new String(decompress(compressedCapture.toByteArray()), UTF_8);
        assertEquals(inputString, decompressed);
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testEncodeSmallBuffer(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        String inputString = "0";
        ByteBuffer input = asDirect(inputString);
        Content.Source uncompressedSource = Content.Source.from(input);

        ByteArrayOutputStream compressedCapture = new ByteArrayOutputStream();
        Content.Sink byteCaptureSink = Content.Sink.from(compressedCapture);
        Content.Sink encoderSink = compression.newEncoderSink(byteCaptureSink);

        Callback.Completable completable = new Callback.Completable();
        Content.copy(uncompressedSource, encoderSink, completable);
        completable.get();

        String decompressed = new String(decompress(compressedCapture.toByteArray()), UTF_8);
        assertEquals(inputString, decompressed);
    }

    @ParameterizedTest
    @MethodSource("textInputs")
    public void testEncodeText(Class<Compression> compressionClass, String textResourceName) throws Exception
    {
        startCompression(compressionClass);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);

        ByteBufferPool.Sized sizedPool = new ByteBufferPool.Sized(pool, true, 4096);
        Content.Source fileSource = Content.Source.from(sizedPool, uncompressed);

        ByteArrayOutputStream compressedCapture = new ByteArrayOutputStream();
        Content.Sink byteCaptureSink = Content.Sink.from(compressedCapture);
        Content.Sink encoderSink = compression.newEncoderSink(byteCaptureSink);

        Callback.Completable callback = new Callback.Completable();
        Content.copy(fileSource, encoderSink, callback);
        callback.get();
        byte[] compressed = compressedCapture.toByteArray();

        // Verify contents
        String decompressed = new String(decompress(compressed), StandardCharsets.UTF_8);
        String expected = Files.readString(uncompressed, StandardCharsets.UTF_8);
        assertEquals(expected, decompressed);
    }

    @ParameterizedTest
    @MethodSource("textInputs")
    public void testEncodeTextSmallReadBuffers(Class<Compression> compressionClass, String textResourceName) throws Exception
    {
        startCompression(compressionClass);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);

        ByteBufferPool.Sized sizedPool = new ByteBufferPool.Sized(pool, true, 4096);
        Content.Source fileSource = Content.Source.from(sizedPool, uncompressed);
        Content.Source maxBufferSource = new MaxBufferContentSource(fileSource, 16);

        ByteArrayOutputStream compressedCapture = new ByteArrayOutputStream();
        Content.Sink byteCaptureSink = Content.Sink.from(compressedCapture);
        Content.Sink encoderSink = compression.newEncoderSink(byteCaptureSink);

        Callback.Completable callback = new Callback.Completable();
        Content.copy(maxBufferSource, encoderSink, callback);
        callback.get();
        byte[] compressed = compressedCapture.toByteArray();

        // Verify contents
        String decompressed = new String(decompress(compressed), StandardCharsets.UTF_8);
        String expected = Files.readString(uncompressed, StandardCharsets.UTF_8);
        assertEquals(expected, decompressed);
    }
}
