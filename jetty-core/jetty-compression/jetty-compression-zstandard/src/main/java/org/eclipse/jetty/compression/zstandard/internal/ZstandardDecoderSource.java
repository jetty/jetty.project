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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.Cleaner;

import com.github.luben.zstd.ZstdDecompressCtx;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.compression.zstandard.ZstandardCompression;
import org.eclipse.jetty.compression.zstandard.ZstandardDecoderConfig;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

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

        ReadableBuffer inputBuffer;
        if (!inputChunk.getByteBuffer().isDirect())
        {
            WritableBuffer wb = compression.acquireBuffer(inputChunk.remaining());
            BufferUtil.put(inputChunk.getByteBuffer(), wb);
            inputBuffer = wb.toReadable();
        }
        else
        {
            inputBuffer = ReadableBuffer.wrap(inputChunk.getByteBuffer());
        }

        WritableBuffer dst = compression.acquireBuffer(bufferSize);
        boolean last = inputChunk.isLast();
        try
        {
            boolean[] fullyFlushed = new boolean[1];
            inputBuffer.writeTo(input ->
            {
                dst.readFrom(output ->
                {
                    fullyFlushed[0] = decompressCtx.decompressDirectByteBufferStream(output, input);
                    return fullyFlushed[0];
                });
            });
            if (!fullyFlushed[0])
                last = false;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException(e);
        }
        ReadableBuffer rb = dst.toReadable();
        inputBuffer.release();
        Content.Chunk chunk = Content.Chunk.asChunk(rb, last, null);
        rb.release();
        return chunk;
    }

    @Override
    public void release()
    {
        cleanable.clean();
    }
}
