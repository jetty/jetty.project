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

import com.github.luben.zstd.ZstdDecompressCtx;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;

public class ZstandardDecoderSource extends DecoderSource
{
    private final ZstandardCompression compression;
    private final ZstdDecompressCtx decompressCtx;
    private final int bufferSize;

    public ZstandardDecoderSource(ZstandardCompression compression, Content.Source src, ZstandardDecoderConfig config)
    {
        super(src);
        this.compression = compression;
        this.decompressCtx = new ZstdDecompressCtx();
        this.decompressCtx.setMagicless(config.isMagicless());
        this.bufferSize = config.getBufferSize();
    }

    @Override
    protected Content.Chunk nextChunk(Content.Chunk readChunk)
    {
        ByteBuffer input = readChunk.getByteBuffer();
        if (!readChunk.hasRemaining())
            return readChunk;
        if (!input.isDirect())
            throw new IllegalArgumentException("Read Chunk is not a Direct ByteBuffer");
        RetainableByteBuffer dst = compression.acquireByteBuffer();
        boolean last = readChunk.isLast();
        dst.getByteBuffer().clear();
        boolean fullyFlushed = decompressCtx.decompressDirectByteBufferStream(dst.getByteBuffer(), input);
        if (!fullyFlushed)
        {
            last = false;
        }
        dst.getByteBuffer().flip();
        return Content.Chunk.asChunk(dst.getByteBuffer(), last, dst);
    }
}
