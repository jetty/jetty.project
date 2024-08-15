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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.Decoder;
import com.aayushatharva.brotli4j.encoder.BrotliEncoderChannel;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
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
public class BrotliEncoderTest extends AbstractBrotliTest
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliEncoderTest.class);
    public WorkDir workDir;

    @Test
    public void testBrotliEncoderChannel() throws IOException
    {
        Brotli4jLoader.ensureAvailability();
        Path textFile = MavenPaths.findTestResourceFile("texts/test_quotes.txt");

        Encoder.Parameters encoderParams = new Encoder.Parameters();
        encoderParams.setQuality(5);

        ByteBuffer output = ByteBuffer.allocate(16000);
        try (SeekableByteChannel fileChannel = Files.newByteChannel(textFile, StandardOpenOption.READ);
             CaptureWritableByteChannel captureWritableByteChannel = new CaptureWritableByteChannel(output);
             BrotliEncoderChannel brotliEncoderChannel = new BrotliEncoderChannel(captureWritableByteChannel, encoderParams))
        {
            ByteBuffer buffer = ByteBuffer.allocate(256);
            int len = 0;
            while (len != -1)
            {
                len = fileChannel.read(buffer);
                if (len > 0)
                {
                    buffer.flip();
                    brotliEncoderChannel.write(buffer);
                    buffer.compact();
                }
            }
        }

        output.flip();
        byte[] compressed = BufferUtil.toArray(output);
        byte[] decompressed = Decoder.decompress(compressed).getDecompressedData();
        String expectedContents = Files.readString(textFile, UTF_8);
        String actualContents = new String(decompressed, UTF_8);
        assertThat(actualContents, is(expectedContents));
    }

    @Test
    public void testBrotliOutputStream() throws IOException
    {
        Brotli4jLoader.ensureAvailability();
        Path textFile = MavenPaths.findTestResourceFile("texts/test_quotes.txt");

        Encoder.Parameters encoderParams = new Encoder.Parameters();
        encoderParams.setQuality(5);

        Path brotliCompressedFile = workDir.getPath().resolve("brotli-outputstream-compressed.br");
        FS.ensureDirExists(brotliCompressedFile.getParent());

        try (InputStream in = Files.newInputStream(textFile);
             OutputStream out = Files.newOutputStream(brotliCompressedFile);
             BrotliOutputStream brotliOutputStream = new BrotliOutputStream(out, encoderParams))
        {
            IO.copy(in, brotliOutputStream);
        }
    }

    @Test
    public void testEncodeSingleBuffer() throws Exception
    {
        startBrotli(2048);

        String inputString = "Hello World, this is " + BrotliEncoderTest.class.getName();
        ByteBuffer input = BufferUtil.toBuffer(inputString, UTF_8);

        try (Compression.Encoder encoder = brotli.newEncoder())
        {
            RetainableByteBuffer compressed = brotli.acquireByteBuffer();
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
        startBrotli(2048);

        String inputString = "Jetty";

        ByteBuffer input = BufferUtil.toBuffer(inputString, UTF_8);
        try (Compression.Encoder encoder = brotli.newEncoder())
        {
            RetainableByteBuffer compressed = brotli.acquireByteBuffer();
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
        startBrotli(2048);

        final int readBufferSize = 1000;

        Path textFile = MavenPaths.findTestResourceFile(resourceName);

        int fileSize = (int)Files.size(textFile);
        ByteBuffer compressed = ByteBuffer.allocate(fileSize);
        RetainableByteBuffer output = brotli.acquireByteBuffer();
        try (Compression.Encoder encoder = brotli.newEncoder();
             SeekableByteChannel channel = Files.newByteChannel(textFile, StandardOpenOption.READ))
        {
            ByteBuffer input = ByteBuffer.allocate(readBufferSize);
            ByteBuffer outputBuf = output.getByteBuffer();

            // input / encode loop
            while (!encoder.isOutputFinished())
            {
                if (encoder.needsInput())
                {
                    int readLen = channel.read(input);
                    input.flip();
                    if (readLen > 0)
                    {
                        encoder.addInput(input);
                    }
                    else if (readLen == (-1))
                    {
                        encoder.finishInput();
                    }
                    input.compact();
                }

                BufferUtil.clearToFill(outputBuf);
                int encodedLen = encoder.encode(outputBuf);
                if (encodedLen > 0)
                {
                    BufferUtil.flipToFlush(outputBuf, 0);
                    compressed.put(outputBuf);
                    BufferUtil.clearToFill(outputBuf);
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
