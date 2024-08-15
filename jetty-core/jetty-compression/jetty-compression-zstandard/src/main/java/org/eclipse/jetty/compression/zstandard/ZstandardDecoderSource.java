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
import com.github.luben.zstd.ZstdException;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.ExceptionUtil;

public class ZstandardDecoderSource implements Content.Source
{
    private final ZstandardCompression compression;
    private final Content.Source source;
    private final ZstdDecompressCtx decompressCtx;
    private Content.Chunk activeChunk;
    private Throwable failed;

    public ZstandardDecoderSource(ZstandardCompression compression, Content.Source src)
    {
        this.compression = compression;
        this.source = src;
        this.decompressCtx = new ZstdDecompressCtx();
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

        ByteBuffer input = readChunk.getByteBuffer();
        RetainableByteBuffer dst = compression.acquireByteBuffer();
        boolean last = readChunk.isLast();
        try
        {
            dst.getByteBuffer().clear();
            boolean fullyFlushed = decompressCtx.decompressDirectByteBufferStream(dst.getByteBuffer(), input);
            if (!fullyFlushed)
            {
                last = false;
            }
            dst.getByteBuffer().flip();
        }
        catch (ZstdException e)
        {
            fail(e);
            return Content.Chunk.from(failed, true);
        }

        if (!readChunk.hasRemaining())
            freeActiveChunk();

        return Content.Chunk.from(dst.getByteBuffer(), last, dst::release);
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
