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
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ExceptionUtil;

public class BrotliDecoderSource implements Content.Source
{
    private static final ByteBuffer EMPTY_BUFFER = BufferUtil.EMPTY_BUFFER;
    private final BrotliCompression compression;
    private final Content.Source source;
    private final DecoderJNI.Wrapper decoder;
    private Content.Chunk activeChunk;
    private long bytesRead; // TODO: Get rid of?
    private Throwable failed;

    public BrotliDecoderSource(BrotliCompression compression, Content.Source source)
    {
        this.compression = compression;
        this.source = source;
        try
        {
            this.decoder = new DecoderJNI.Wrapper(compression.getBufferSize());
        }
        catch (IOException e)
        {
            // TODO: should this just throw IOException instead?
            throw new RuntimeException("Unable to initialize Brotli Decoder", e);
        }
    }

    private Content.Chunk readChunk()
    {
        if (activeChunk != null)
        {
            if (activeChunk.hasRemaining())
                return activeChunk;
            else
            {
                activeChunk.release();
                activeChunk = null;
            }
        }

        activeChunk = source.read();
        return activeChunk;
    }

    private void freeActiveChunk()
    {
        activeChunk.release();
        activeChunk = null;
    }

    @Override
    public Content.Chunk read()
    {
        if (failed != null)
            return Content.Chunk.from(failed, true);

        Content.Chunk readChunk = readChunk();
        boolean last = readChunk.isLast();
        ByteBuffer compressed = readChunk.getByteBuffer();

        try
        {
            Content.Chunk output = parse(compressed, last);
            return output;
        }
        catch (IOException e)
        {
            fail(e);
            return Content.Chunk.from(failed, true);
        }
        finally
        {
            if (!readChunk.hasRemaining())
                freeActiveChunk();
        }
    }

    private Content.Chunk parse(ByteBuffer compressed, boolean last) throws IOException
    {
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
                    bytesRead += len;
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
                    throw new IOException("Corrupted input buffer");
                }
            }
        }
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        demandCallback.run();
    }

    @Override
    public void fail(Throwable failure)
    {
        failed = ExceptionUtil.combine(failed, failure);
        source.fail(failure);
    }
}
