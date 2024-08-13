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
import java.nio.ByteBuffer;

import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brotli Decoder (decompress)
 */
public class BrotliDecoder implements Compression.Decoder
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliDecoder.class);

    private final int bufferSize;
    private DecoderJNI.Wrapper decoder;
    private boolean finishedInput = false;
    private long bytesRead = 0;

    public BrotliDecoder(BrotliCompression brotliCompression)
    {
        this.bufferSize = brotliCompression.getBufferSize();
        try
        {
            this.decoder = new DecoderJNI.Wrapper(this.bufferSize);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to initialize Brotli Decoder", e);
        }
    }

    @Override
    public void close()
    {
        decoder.destroy();
    }

    /**
     * <p>Decompresses compressed data from a buffer.</p>
     *
     * <p>
     * The {@link RetainableByteBuffer} returned by this method
     * <em>must</em> be released via {@link RetainableByteBuffer#release()}.
     * </p>
     *
     * <p>
     * This method may fully consume the input buffer, but return
     * only a chunk of the decompressed bytes, to allow applications to
     * consume the decompressed buffer before performing further decompression,
     * applying backpressure. In this case, this method should be
     * invoked again with the same input buffer (even if
     * it's already fully consumed) and that will produce another
     * buffer of decompressed bytes. Termination happens when the input
     * buffer is fully consumed, and the returned buffer is empty.
     * </p>
     *
     * @param compressed the buffer containing compressed data.
     * @return a buffer containing decompressed data.
     * @throws IOException if unable to decompress the input buffer
     */
    public RetainableByteBuffer decode(ByteBuffer compressed) throws IOException
    {
        if (finishedInput && compressed.hasRemaining())
            throw new IllegalStateException("finishInput already called, cannot read input buffer");

        RetainableByteBuffer output = null;
        while (output == null)
        {
            switch (decoder.getStatus())
            {
                case DONE ->
                {
                    output = RetainableByteBuffer.EMPTY;
                    break;
                }
                case OK ->
                {
                    decoder.push(0);
                }
                case NEEDS_MORE_INPUT ->
                {
                    ByteBuffer input = decoder.getInputBuffer();
                    BufferUtil.clearToFill(input);
                    int len = BufferUtil.put(compressed, input);
                    bytesRead += len;
                    decoder.push(len);

                    if (len == 0)
                    {
                        output = RetainableByteBuffer.EMPTY;
                        break;
                    }
                }
                case NEEDS_MORE_OUTPUT ->
                {
                    ByteBuffer pulled = decoder.pull();
                    output = RetainableByteBuffer.wrap(pulled);
                    break;
                }
                default ->
                {
                    throw new IOException("Corrupted input buffer");
                }
            }
        }
        return output;
    }

    @Override
    public void finishInput() throws IOException
    {
        if (finishedInput)
            return;

        finishedInput = true;

        if (bytesRead > 0)
        {
            DecoderJNI.Status status = decoder.getStatus();
            if (status != DecoderJNI.Status.OK && status != DecoderJNI.Status.DONE)
                throw new IOException(String.format("Decoder failure [%s]", status.name()));
        }
    }

    @Override
    public boolean isOutputComplete()
    {
        return switch (decoder.getStatus())
        {
            case ERROR, DONE -> true;
            case NEEDS_MORE_OUTPUT -> false;
            default -> decoder.hasOutput();
        };
    }
}
