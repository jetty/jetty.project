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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.aayushatharva.brotli4j.decoder.Decoder;
import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import com.aayushatharva.brotli4j.decoder.DirectDecompress;
import com.aayushatharva.brotli4j.encoder.BrotliEncoderChannel;
import com.aayushatharva.brotli4j.encoder.BrotliOutputStream;
import com.aayushatharva.brotli4j.encoder.Encoder;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(WorkDirExtension.class)
public class BrotliEncoderTest extends AbstractBrotliTest
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliEncoderTest.class);
    public WorkDir workDir;

    @Test
    public void testEncodeSingleBuffer() throws Exception
    {
        startBrotli(2048);

        BrotliEncoder encoder = (BrotliEncoder)brotli.newEncoder(2048);

        String inputString = "Hello World, this is " + BrotliEncoderTest.class.getName();
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
            encoder.release();
            output.release();
        }
    }

    @Test
    public void testBrotliOutputStream() throws IOException
    {
        Brotli4jLoader.ensureAvailability();
        Path textFile = MavenPaths.findTestResourceFile("precompressed/test_quotes.txt");

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
    public void testBrotliEncoderChannel() throws IOException
    {
        Brotli4jLoader.ensureAvailability();
        Path textFile = MavenPaths.findTestResourceFile("precompressed/test_quotes.txt");

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
        String expectedContents = Files.readString(textFile, StandardCharsets.UTF_8);
        String actualContents = new String(decompressed, StandardCharsets.UTF_8);
        assertThat(actualContents, is(expectedContents));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "precompressed/test_quotes.txt",
        "precompressed/text-long.txt",
    })
    public void testEncodeTextMultipleSmallBuffers(String resourceName) throws Exception
    {
        startBrotli(2048);

        final int readBufferSize = 1000;

        BrotliEncoder encoder = (BrotliEncoder)brotli.newEncoder(2048);

        Path textFile = MavenPaths.findTestResourceFile(resourceName);

        int fileSize = (int)Files.size(textFile);
        System.err.printf("fileSize = %d%n", fileSize);
        int encodeMax = (int)(double)(fileSize / readBufferSize) + 10;
        System.err.printf("encodeMax = %d%n", encodeMax);

        ByteBuffer compressed = ByteBuffer.allocate(fileSize);
        RetainableByteBuffer output = encoder.acquireInitialOutputBuffer();
        try (SeekableByteChannel channel = Files.newByteChannel(textFile, StandardOpenOption.READ))
        {
            ByteBuffer input = ByteBuffer.allocate(readBufferSize);

            ByteBuffer outputBuf = output.getByteBuffer();
            outputBuf.order(encoder.getByteOrder());
            encoder.begin();

            // input / encode loop
            while (!encoder.isOutputFinished())
            {
                if (encoder.needsInput())
                {
                    int readLen = channel.read(input);
                    input.flip();
                    if (readLen > 0)
                    {
                        encoder.setInput(input);
                    }
                    else if (readLen == (-1))
                    {
                        encoder.finishInput();
                    }
                    input.compact();
                }

                int encodedLen = encoder.encode(outputBuf);
                if (encodeMax-- < 0)
                    throw new RuntimeIOException("DEBUG: Too many encodes!");

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
            LOG.debug("Compressed={}", BufferUtil.toDetailString(compressed));

            String decompressed = decompress(compressed);
            String wholeText = Files.readString(textFile, StandardCharsets.UTF_8);
            assertThat(decompressed, is(wholeText));
        }
        finally
        {
            encoder.release();
            output.release();
        }
    }

    private String decompress(ByteBuffer buf) throws IOException
    {
        byte[] array = BufferUtil.toArray(buf.slice());

        if (LOG.isDebugEnabled())
        {
            Path compressedPath = workDir.getPath().resolve("compressed.br");
            FS.ensureDirExists(compressedPath.getParent());
            Files.write(compressedPath, array);
        }

        DirectDecompress directDecompress = Decoder.decompress(array);
        assertNotNull(directDecompress, "Decompress failed: returned null");
        assertThat(directDecompress.getResultStatus(), is(not(DecoderJNI.Status.ERROR)));
        byte[] decompressed = directDecompress.getDecompressedData();
        assertNotNull(decompressed, "Decompress failed, no bytes produced");
        return new String(decompressed, StandardCharsets.UTF_8);
    }

    private static class CaptureWritableByteChannel implements WritableByteChannel
    {
        private final ByteBuffer buffer;

        public CaptureWritableByteChannel(ByteBuffer buffer)
        {
            this.buffer = buffer;
        }

        @Override
        public int write(ByteBuffer src)
        {
            int pos = buffer.position();
            buffer.put(src);
            return buffer.position() - pos;
        }

        @Override
        public boolean isOpen()
        {
            return true;
        }

        @Override
        public void close()
        {
        }
    }
}
