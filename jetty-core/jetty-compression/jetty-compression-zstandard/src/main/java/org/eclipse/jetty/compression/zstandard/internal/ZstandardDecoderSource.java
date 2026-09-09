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

package org.eclipse.jetty.compression.zstandard.internal;

import java.lang.ref.Cleaner;

import com.github.luben.zstd.ZstdDecompressCtx;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardDecoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class ZstandardDecoderSource extends DecoderSource
{
    private final ZstandardCompression compression;
    private final ZstdDecompressCtx decompressCtx;
    private final int bufferSize;
    private final Cleaner.Cleanable cleanable;

    public ZstandardDecoderSource(Content.Source source, ZstandardCompression compression, ZstandardDecoderConfig config)
    {
        super(source);
        this.compression = compression;
        this.decompressCtx = new ZstdDecompressCtx();
        this.decompressCtx.setMagicless(config.isMagicless());
        this.bufferSize = config.getBufferSize();
        this.cleanable = compression.getCleaner().register(this, decompressCtx::close);
    }

    @Override
    protected Content.Chunk transform(Content.Chunk inputChunk)
    {
        if (inputChunk.isEmpty() && inputChunk.isLast())
            return inputChunk;

        RetainableByteBuffer inputBuffer;
        if (!inputChunk.getByteBuffer().isDirect())
        {
            RetainableByteBuffer.Mutable b = compression.acquireBuffer(inputChunk.remaining());
            b.put(inputChunk.getByteBuffer());
            inputBuffer = b;
        }
        else
        {
            inputBuffer = RetainableByteBuffer.wrap(inputChunk.getByteBuffer());
        }

        boolean last = inputChunk.isLast();
        RetainableByteBuffer.Mutable outputBuffer = compression.acquireBuffer(bufferSize);
        boolean[] fullyFlushed = new boolean[1];
        inputBuffer.quietWriteTo(input ->
        {
            int r = input.remaining();
            outputBuffer.readFrom(output ->
            {
                int p = output.position();
                fullyFlushed[0] = decompressCtx.decompressDirectByteBufferStream(output, input);
                return output.position() - p;
            });
            return r - input.remaining();
        });
        if (!fullyFlushed[0])
            last = false;
        Content.Chunk chunk = Content.Chunk.asChunk(outputBuffer, last, null);
        outputBuffer.release();
        inputBuffer.release();
        return chunk;
    }

    @Override
    public void release()
    {
        cleanable.clean();
    }
}
