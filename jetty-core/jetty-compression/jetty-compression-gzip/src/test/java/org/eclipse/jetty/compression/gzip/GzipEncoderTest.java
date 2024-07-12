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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

public class GzipEncoderTest extends AbstractGzipTest
{
    private static final Logger LOG = LoggerFactory.getLogger(GzipEncoderTest.class);

    @Test
    public void testEncodeSingleBuffer() throws Exception
    {
        startGzip(2048);

        GzipEncoder encoder = (GzipEncoder)gzip.newEncoder(2048);

        String inputString = "Hello World, this is " + GzipEncoderTest.class.getName();
        ByteBuffer input = BufferUtil.toBuffer(inputString);

        RetainableByteBuffer output = encoder.acquireInitialOutputBuffer();
        try
        {
            ByteBuffer outputBuf = output.getByteBuffer();
            encoder.begin();
            encoder.setInput(input);
            encoder.finishInput();
            encoder.encode(outputBuf);
            encoder.addTrailer(outputBuf);

            BufferUtil.flipToFlush(outputBuf, 0);

            assertThat(output.hasRemaining(), is(true));
            assertThat(output.remaining(), greaterThan(10));

            String decompressed = decompress(outputBuf);
            assertThat(decompressed, is(inputString));
        }
        finally
        {
            output.release();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "precompressed/test_quotes.txt",
        "precompressed/text-long.txt",
    })
    public void testEncodeTextMultipleSmallBuffers(String resourceName) throws Exception
    {
        startGzip(2048);

        GzipEncoder encoder = (GzipEncoder)gzip.newEncoder(2048);

        Path textFile = MavenPaths.findTestResourceFile(resourceName);

        RetainableByteBuffer.DynamicCapacity aggregateCompressed = new RetainableByteBuffer.DynamicCapacity();

        RetainableByteBuffer output = encoder.acquireInitialOutputBuffer();
        try (SeekableByteChannel channel = Files.newByteChannel(textFile))
        {
            ByteBuffer input = ByteBuffer.allocate(256);

            ByteBuffer outputBuf = output.getByteBuffer();
            outputBuf.order(encoder.getByteOrder());
            encoder.begin();

            // input / encode loop
            while (!encoder.isOutputFinished())
            {
                if (encoder.needsInput())
                {
                    BufferUtil.clearToFill(input);
                    int readLen = channel.read(input);
                    if (readLen > 0)
                    {
                        BufferUtil.flipToFlush(input, 0);
                        encoder.setInput(input);
                    }
                    else if (readLen == (-1))
                    {
                        encoder.setInput(BufferUtil.EMPTY_BUFFER);
                        encoder.finishInput();
                    }
                }

                int encodedLen = encoder.encode(outputBuf);
                if (encodedLen > 0 || outputBuf.hasRemaining())
                {
                    BufferUtil.flipToFlush(outputBuf, 0);
                    aggregateCompressed.append(outputBuf);
                    BufferUtil.clearToFill(outputBuf);
                }
            }

            encoder.addTrailer(outputBuf);
            BufferUtil.flipToFlush(outputBuf, 0);
            aggregateCompressed.append(outputBuf);
            BufferUtil.clearToFill(outputBuf);

            ByteBuffer compressed = aggregateCompressed.getByteBuffer();
            String decompressed = decompress(compressed);
            String wholeText = Files.readString(textFile, StandardCharsets.UTF_8);
            assertThat(decompressed, is(wholeText));
        }
        finally
        {
            output.release();
        }
    }

    private String decompress(ByteBuffer buf) throws IOException
    {
        byte[] array = BufferUtil.toArray(buf.slice());

        try (
            ByteArrayInputStream input = new ByteArrayInputStream(array);
            GZIPInputStream gzipInput = new GZIPInputStream(input);
            ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            IO.copy(gzipInput, output);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
