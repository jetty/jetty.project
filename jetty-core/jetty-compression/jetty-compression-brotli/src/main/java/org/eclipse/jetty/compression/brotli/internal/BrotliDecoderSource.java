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

package org.eclipse.jetty.compression.brotli.internal;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.aayushatharva.brotli4j.decoder.DecoderJNI;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.brotli.BrotliCompression;
import org.eclipse.jetty.compression.brotli.BrotliDecoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class BrotliDecoderSource extends DecoderSource
{
    private final DecoderJNI.Wrapper decoder;
    private final BrotliCompression compression;
    private boolean released;
    private boolean failed;

    public BrotliDecoderSource(Content.Source source, BrotliCompression compression, BrotliDecoderConfig config)
    {
        super(source);
        this.compression = compression;
        try
        {
            this.decoder = new DecoderJNI.Wrapper(config.getBufferSize());
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to initialize Brotli Decoder", e);
        }
    }

    @Override
    protected Content.Chunk transform(Content.Chunk inputChunk)
    {
        ByteBuffer compressed = inputChunk.getByteBuffer();
        if (inputChunk.isLast() && !inputChunk.hasRemaining())
            return Content.Chunk.EOF;

        boolean last = inputChunk.isLast();

        while (true)
        {
            switch (decoder.getStatus())
            {
                case DONE ->
                {
                    return last ? Content.Chunk.EOF : Content.Chunk.EMPTY;
                }
                case OK -> decoder.push(0);
                case NEEDS_MORE_INPUT ->
                {
                    ByteBuffer input = decoder.getInputBuffer();
                    BufferUtil.clearToFill(input);
                    int len = BufferUtil.put(compressed, input);
                    decoder.push(len);

                    if (len == 0)
                    {
                        // rely on status.OK to go to EOF.
                        return Content.Chunk.EMPTY;
                    }
                }
                case NEEDS_MORE_OUTPUT ->
                {
                    ByteBuffer output = decoder.pull();
                    // Rely on status.OK to go to EOF.
                    int remaining = output.remaining();
                    if (remaining == 0)
                        return Content.Chunk.EMPTY;
                    RetainableByteBuffer.Mutable copy = compression.acquireByteBuffer(remaining);
                    copy.append(output);
                    return Content.Chunk.asChunk(copy.getByteBuffer(), false, copy);
                }
                default ->
                {
                    return Content.Chunk.from(new IOException("Decoder failure: Corrupted input buffer"));
                }
            }
        }
    }

    @Override
    public void fail(Throwable failure)
    {
        super.fail(failure);
        failed = true;
    }

    // Only used for testing.
    public boolean isReleased()
    {
        return released;
    }

    // Only used for testing.
    public String getStatus()
    {
        return failed ? "FAILED" : decoder.getStatus().toString();
    }

    @Override
    protected void release()
    {
        if (!released)
        {
            decoder.destroy();
            released = true;
        }
    }
}
