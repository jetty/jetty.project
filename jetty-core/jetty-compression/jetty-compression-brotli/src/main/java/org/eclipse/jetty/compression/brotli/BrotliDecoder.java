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
import java.util.Objects;

import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrotliDecoder implements Compression.Decoder
{
    private static final Logger LOG = LoggerFactory.getLogger(BrotliDecoder.class);

    private final ByteBufferPool pool;
    private final int bufferSize;
    private DecoderJNI.Wrapper decoder;
    private RetainableByteBuffer output;

    public BrotliDecoder(BrotliCompression brotliCompression, ByteBufferPool pool)
    {
        this.pool = Objects.requireNonNull(pool);
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
    public RetainableByteBuffer decode(Content.Chunk chunk) throws IOException
    {
        while (true)
        {
            if (output != null)
            {
                if (!output.hasRemaining())
                {
                    output = null;
                }
                else
                {
                    return output;
                }
            }

            switch (decoder.getStatus())
            {
                case DONE ->
                {
                    return null;
                }
                case OK ->
                {
                    decoder.push(0);
                }
                case NEEDS_MORE_INPUT ->
                {
                    ByteBuffer compressed = chunk.getByteBuffer();
                    decoder.getInputBuffer().put(compressed);
                    decoder.push(compressed.remaining());
                }
                case NEEDS_MORE_OUTPUT ->
                {
                    ByteBuffer pulled = decoder.pull();
                    output = RetainableByteBuffer.wrap(pulled);
                    return output;
                }
                default ->
                {
                    throw new IOException("Corrupted input");
                }
            }
        }
    }

    @Override
    public void cleanup()
    {
        decoder.destroy();
    }
}
