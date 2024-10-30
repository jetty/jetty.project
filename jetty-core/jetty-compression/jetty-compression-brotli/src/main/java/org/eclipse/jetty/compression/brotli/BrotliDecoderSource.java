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
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;

public class BrotliDecoderSource extends DecoderSource
{
    private static final ByteBuffer EMPTY_BUFFER = BufferUtil.EMPTY_BUFFER;
    private final BrotliCompression compression;
    private final DecoderJNI.Wrapper decoder;

    public BrotliDecoderSource(BrotliCompression compression, Content.Source source, BrotliDecoderConfig config)
    {
        super(source);
        this.compression = compression;
        try
        {
            this.decoder = new DecoderJNI.Wrapper(compression.getBufferSize());
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to initialize Brotli Decoder", e);
        }
    }

    @Override
    protected Content.Chunk nextChunk(Content.Chunk readChunk) throws IOException
    {
        ByteBuffer compressed = readChunk.getByteBuffer();
        if (readChunk.isLast() && !readChunk.hasRemaining())
            return Content.Chunk.EOF;

        boolean last = readChunk.isLast();

        while (true)
        {
            switch (decoder.getStatus())
            {
                case DONE ->
                {
                    return last ? Content.Chunk.EOF : Content.Chunk.EMPTY;
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
                    // rely on status.OK to go to EOF
                    return Content.Chunk.from(output, false);
                }
                default ->
                {
                    throw new IOException("Decoder failure: Corrupted input buffer");
                }
            }
        }
    }

    @Override
    protected void release()
    {
        decoder.destroy();
    }
}
