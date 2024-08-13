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

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

public class CompressionEncoderTest extends AbstractCompressionTest
{
    private static final Logger LOG = LoggerFactory.getLogger(CompressionEncoderTest.class);

    @ParameterizedTest
    @MethodSource("compressions")
    public void testEncodeSingleBuffer(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        String inputString = "Hello World, this is " + CompressionEncoderTest.class.getName();
        ByteBuffer input = BufferUtil.toBuffer(inputString, UTF_8);

        try (Compression.Encoder encoder = compression.newEncoder())
        {
            RetainableByteBuffer compressed = compression.acquireByteBuffer();
            ByteBuffer compressedBytes = compressed.getByteBuffer();
            BufferUtil.flipToFill(compressedBytes);
            encoder.addInput(input);
            encoder.finishInput();
            encoder.encode(compressedBytes);
            encoder.addTrailer(compressedBytes);

            BufferUtil.flipToFlush(compressedBytes, 0);

            assertThat(compressed.hasRemaining(), is(true));
            assertThat(compressed.remaining(), greaterThan(10));

            String decompressed = new String(decompress(compressedBytes), UTF_8);
            assertThat(decompressed, is(inputString));
            compressed.release();
        }
    }

    @ParameterizedTest
    @MethodSource("compressions")
    public void testEncodeSmallBuffer(Class<Compression> compressionClass) throws Exception
    {
        startCompression(compressionClass);

        String inputString = "Jetty";
        ByteBuffer input = BufferUtil.toBuffer(inputString, UTF_8);
        try (Compression.Encoder encoder = compression.newEncoder())
        {
            RetainableByteBuffer compressed = compression.acquireByteBuffer();
            ByteBuffer compressedBytes = compressed.getByteBuffer();
            BufferUtil.flipToFill(compressedBytes);
            encoder.addInput(input);
            encoder.finishInput();
            encoder.encode(compressedBytes);
            encoder.addTrailer(compressedBytes);

            BufferUtil.flipToFlush(compressedBytes, 0);

            assertThat(compressed.hasRemaining(), is(true));
            assertThat(compressed.remaining(), greaterThan(10));

            String decompressed = new String(decompress(compressedBytes), UTF_8);
            assertThat(decompressed, is(inputString));
            compressed.release();
        }
    }

    @ParameterizedTest
    @MethodSource("textInputs")
    public void testEncodeTextMultipleSmallBuffers(Class<Compression> compressionClass, String resourceName) throws Exception
    {
        startCompression(compressionClass);

        try (Compression.Encoder encoder = compression.newEncoder())
        {
            Path textFile = MavenPaths.findTestResourceFile(resourceName);

            long fileSize = Files.size(textFile);
            // since we are working with a simple aggregate byte buffers, lets put a small safety check in
            assertThat("Test not able to support large file sizes", fileSize, lessThan(8_000_000L));

            int aggregateSize = (int)(fileSize * 2);
            ByteBuffer aggregate = ByteBuffer.allocate(aggregateSize);

            RetainableByteBuffer output = compression.acquireByteBuffer();
            try (SeekableByteChannel channel = Files.newByteChannel(textFile))
            {
                ByteBuffer input = ByteBuffer.allocate(256);
                ByteBuffer outputBuf = output.getByteBuffer();

                boolean inputDone = false;
                boolean outputDone = false;
                // input / encode loop
                while (!inputDone || !outputDone)
                {
                    if (encoder.needsInput() && !inputDone)
                    {
                        if (!input.hasRemaining())
                            BufferUtil.clearToFill(input);
                        int readLen = channel.read(input);
                        if (readLen > 0)
                        {
                            BufferUtil.flipToFlush(input, 0);
                            encoder.addInput(input);
                        }
                        else if (readLen == (-1))
                        {
                            // ensure that input is empty (and not using previous input)
                            encoder.addInput(BufferUtil.EMPTY_BUFFER);
                            encoder.finishInput();
                            inputDone = true;
                        }
                    }

                    BufferUtil.clearToFill(outputBuf);
                    int encodedLen = encoder.encode(outputBuf);
                    if (encodedLen > 0)
                    {
                        BufferUtil.flipToFlush(outputBuf, 0);
                        aggregate.put(outputBuf);
                    }
                    else if (encodedLen == 0 && inputDone)
                    {
                        outputDone = true;
                    }
                }

                BufferUtil.clearToFill(outputBuf);
                encoder.addTrailer(outputBuf);
                BufferUtil.flipToFlush(outputBuf, 0);
                aggregate.put(outputBuf);

                BufferUtil.flipToFlush(aggregate, 0);

                LOG.debug("File Size = {}", fileSize);

                String decompressed = new String(decompress(aggregate), UTF_8);
                String wholeText = Files.readString(textFile, UTF_8);
                assertThat(decompressed, is(wholeText));
            }
            finally
            {
                output.release();
            }
        }
    }
}
