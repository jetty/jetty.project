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

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

@Disabled("Individual tests to be moved to jetty-compression-tests")
@ExtendWith(WorkDirExtension.class)
public class ZstandardEncoderTest extends AbstractZstdTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ZstandardEncoderTest.class);
    public WorkDir workDir;

    @Test
    public void testEncodeSingleBuffer() throws Exception
    {
        startZstd(2048);

        String inputString = "Hello World, this is " + ZstandardEncoderTest.class.getName();
        ByteBuffer input = asDirect(inputString);

        try (Compression.Encoder encoder = zstd.newEncoder())
        {
            RetainableByteBuffer compressed = zstd.acquireByteBuffer();
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
        startZstd(2048);

        String inputString = "Jetty";

        ByteBuffer input = asDirect(inputString);
        try (Compression.Encoder encoder = zstd.newEncoder())
        {
            RetainableByteBuffer compressed = zstd.acquireByteBuffer();
            ByteBuffer compressedBytes = compressed.getByteBuffer();
            BufferUtil.flipToFill(compressedBytes);
            encoder.addInput(input);
            encoder.finishInput();
            encoder.encode(compressedBytes);
            encoder.addTrailer(compressedBytes);

            BufferUtil.flipToFlush(compressedBytes, 0);

            assertThat(compressed.hasRemaining(), is(true));
            assertThat(compressed.remaining(), greaterThan(1));

            String decompressed = new String(decompress(compressedBytes), UTF_8);
            assertThat(decompressed, is(inputString));
            compressed.release();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "texts/test_quotes.txt",
        "texts/text-long.txt",
        "texts/logo.svg",
    })
    public void testEncodeTextMultipleSmallBuffers(String resourceName) throws Exception
    {
        startZstd(2048);

        final int readBufferSize = 64000;

        Path textFile = MavenPaths.findTestResourceFile(resourceName);

        int fileSize = (int)Files.size(textFile);
        ByteBuffer compressed = ByteBuffer.allocate(fileSize);
        RetainableByteBuffer output = zstd.acquireByteBuffer();
        try (Compression.Encoder encoder = zstd.newEncoder();
             SeekableByteChannel channel = Files.newByteChannel(textFile, StandardOpenOption.READ))
        {
            ByteBuffer input = ByteBuffer.allocateDirect(readBufferSize);
            ByteBuffer outputBuf = output.getByteBuffer();

            // input / encode loop
            while (!encoder.isOutputFinished())
            {
                if (encoder.needsInput())
                {
                    input.clear();
                    int readLen = channel.read(input);
                    input.flip();
                    if (readLen == -1)
                        encoder.finishInput();
                    else if (readLen > 0)
                        encoder.addInput(input);
                }

                boolean flushing = true;
                while (flushing)
                {
                    outputBuf.clear();
                    int encodedLen = encoder.encode(outputBuf);
                    outputBuf.flip();
                    if (encodedLen > 0)
                        compressed.put(outputBuf);
                    else if (encodedLen == 0)
                        flushing = false;
                }
            }

            encoder.addTrailer(outputBuf);

            BufferUtil.flipToFlush(outputBuf, 0);
            compressed.put(outputBuf);
            BufferUtil.clearToFill(outputBuf);
            compressed.flip();

            String decompressed = new String(decompress(compressed), UTF_8);
            String wholeText = Files.readString(textFile, UTF_8);
            assertThat(decompressed, is(wholeText));
        }
        finally
        {
            output.release();
        }
    }

    private static class CaptureWritableByteChannel implements WritableByteChannel
    {
        private final ByteBuffer buffer;

        public CaptureWritableByteChannel(ByteBuffer buffer)
        {
            this.buffer = buffer;
        }

        @Override
        public void close()
        {
        }

        @Override
        public boolean isOpen()
        {
            return true;
        }

        @Override
        public int write(ByteBuffer src)
        {
            int pos = buffer.position();
            buffer.put(src);
            return buffer.position() - pos;
        }
    }
}
