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

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

@Disabled("Individual tests to be moved to jetty-compression-tests")
public class GzipEncoderTest extends AbstractGzipTest
{
    private static final Logger LOG = LoggerFactory.getLogger(GzipEncoderTest.class);

    @Test
    public void testEncodeSingleBuffer() throws Exception
    {
        startGzip(2048);

        String inputString = "Hello World, this is " + GzipEncoderTest.class.getName();
        ByteBuffer input = BufferUtil.toBuffer(inputString, UTF_8);

        try (Compression.Encoder encoder = gzip.newEncoder())
        {
            RetainableByteBuffer compressed = gzip.acquireByteBuffer();
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

    @Test
    public void testEncodeSmallBuffer() throws Exception
    {
        startGzip(2048);

        String inputString = "Jetty";
        ByteBuffer input = BufferUtil.toBuffer(inputString, UTF_8);
        try (Compression.Encoder encoder = gzip.newEncoder())
        {
            RetainableByteBuffer compressed = gzip.acquireByteBuffer();
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
    @ValueSource(strings = {
        "texts/test_quotes.txt",
        "texts/text-long.txt",
    })
    public void testEncodeTextMultipleSmallBuffers(String resourceName) throws Exception
    {
        startGzip(2048);

        try (Compression.Encoder encoder = gzip.newEncoder())
        {
            Path textFile = MavenPaths.findTestResourceFile(resourceName);

            long fileSize = Files.size(textFile);
            // since we are working with a simple aggregate byte buffers, lets put a small safety check in
            assertThat("Test not able to support large file sizes", fileSize, lessThan(8_000_000L));

            int aggregateSize = (int)(fileSize * 2);
            ByteBuffer aggregate = ByteBuffer.allocate(aggregateSize);

            RetainableByteBuffer output = gzip.acquireByteBuffer();
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
