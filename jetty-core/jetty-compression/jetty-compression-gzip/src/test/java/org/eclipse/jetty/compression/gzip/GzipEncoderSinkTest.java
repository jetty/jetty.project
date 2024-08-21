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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.Deflater;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GzipEncoderSinkTest extends AbstractGzipTest
{
    @ParameterizedTest
    @MethodSource("textResources")
    public void testEncodeText(String textResourceName) throws Exception
    {
        startGzip();
        gzip.getDefaultEncoderConfig().setCompressionLevel(Deflater.BEST_COMPRESSION);
        Path uncompressed = MavenPaths.findTestResourceFile(textResourceName);
        byte[] compressed = null;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink encoderSink = gzip.newEncoderSink(fileSink);

            Content.Source fileSource = Content.Source.from(sizedPool, uncompressed);
            Callback.Completable callback = new Callback.Completable();
            Content.copy(fileSource, encoderSink, callback);
            callback.get();
            compressed = baos.toByteArray();
        }

        Path outputPath = MavenPaths.targetTestDir("testEncodeText");
        Path outputTestFile = outputPath.resolve("%s.test.%s".formatted(textResourceName, gzip.getFileExtensionNames().get(0)));
        FS.ensureDirExists(outputTestFile.getParent());
        Files.write(outputTestFile, compressed, CREATE, WRITE);
        System.out.println("Saved: " + outputTestFile);
        Path outputDefaultFile = outputPath.resolve("%s.default.%s".formatted(textResourceName, gzip.getFileExtensionNames().get(0)));
        FS.ensureDirExists(outputDefaultFile.getParent());
        byte[] defcompressed = compress(Files.readString(uncompressed));
        Files.write(outputDefaultFile, defcompressed, CREATE, WRITE);
        System.out.println("Saved: " + outputDefaultFile);

        // Verify contents
        String decompressed = new String(decompress(compressed), UTF_8);
        String expected = Files.readString(uncompressed, UTF_8);
        assertEquals(expected, decompressed);
    }

    @Test
    public void testHelloWorldSingleBuffer() throws Exception
    {
        startGzip();
        gzip.getDefaultEncoderConfig().setCompressionLevel(Deflater.BEST_COMPRESSION);

        byte[] compressed = null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink writeLogger = new WriteLoggerSink(fileSink);
            Content.Sink encoderSink = gzip.newEncoderSink(writeLogger);

            Callback.Completable callback;

            callback = new Callback.Completable();
            encoderSink.write(true, ByteBuffer.wrap("Hello World!".getBytes(UTF_8)), callback);
            callback.get();

            compressed = baos.toByteArray();
        }

        Path outputDir = MavenPaths.targetTestDir("testHelloWorldSingleBuffer");
        FS.ensureDirExists(outputDir);
        Files.write(outputDir.resolve("hw.test.txt.gz"), compressed, WRITE, CREATE);

        byte[] streamCompressed = compress("Hello World!");
        Files.write(outputDir.resolve("hw.stream.txt.gz"), streamCompressed, WRITE, CREATE);

        String result = new String(decompress(compressed), UTF_8);
        assertThat(result, is("Hello World!"));
    }

    @Test
    public void testHelloWorldSplit() throws Exception
    {
        startGzip();
        gzip.getDefaultEncoderConfig().setCompressionLevel(Deflater.BEST_COMPRESSION);

        byte[] compressed = null;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream())
        {
            Content.Sink fileSink = Content.Sink.from(baos);
            Content.Sink writeLogger = new WriteLoggerSink(fileSink);
            Content.Sink encoderSink = gzip.newEncoderSink(writeLogger);

            Callback.Completable callback;

            callback = new Callback.Completable();
            encoderSink.write(false, ByteBuffer.wrap("Hello".getBytes(UTF_8)), callback);
            callback.get();

            callback = new Callback.Completable();
            encoderSink.write(false, ByteBuffer.wrap(" World".getBytes(UTF_8)), callback);
            callback.get();

            callback = new Callback.Completable();
            encoderSink.write(true, ByteBuffer.wrap("!".getBytes(UTF_8)), callback);
            callback.get();

            compressed = baos.toByteArray();
        }

        Path outputDir = MavenPaths.targetTestDir("testHelloWorldSplit");
        FS.ensureDirExists(outputDir);
        Files.write(outputDir.resolve("hw.test.txt.gz"), compressed, WRITE, CREATE);

        byte[] streamCompressed = compress("Hello World!");
        Files.write(outputDir.resolve("hw.stream.txt.gz"), streamCompressed, WRITE, CREATE);

        String result = new String(decompress(compressed), UTF_8);
        assertThat(result, is("Hello World!"));
    }

    private static class WriteLoggerSink implements Content.Sink
    {
        private static final Logger LOG = LoggerFactory.getLogger(WriteLoggerSink.class);
        private final Content.Sink sink;

        public WriteLoggerSink(Content.Sink sink)
        {
            this.sink = sink;
        }

        @Override
        public void write(boolean last, ByteBuffer byteBuffer, Callback callback)
        {
            if (LOG.isDebugEnabled())
                LOG.debug(".write() last={}, byteBuffer={}, callback={}", last, BufferUtil.toDetailString(byteBuffer), callback);
            sink.write(last, byteBuffer, callback);
        }
    }
}
