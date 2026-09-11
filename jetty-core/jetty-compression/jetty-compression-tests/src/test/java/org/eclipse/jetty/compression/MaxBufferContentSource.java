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

package org.eclipse.jetty.compression;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class MaxBufferContentSource implements Content.Source
{
    private final Content.Source delegate;
    private final int maxSize;
    private Content.Chunk activeChunk;
    private Throwable failed;

    public MaxBufferContentSource(Content.Source source, int maxSize)
    {
        this.delegate = source;
        this.maxSize = maxSize;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        if (activeChunk != null)
            demandCallback.run();
        else
            delegate.demand(demandCallback);
    }

    @Override
    public void fail(Throwable failure)
    {
        activeChunk = Retainable.dispose(activeChunk);
        failed = ExceptionUtil.combine(failed, failure);
        delegate.fail(failure);
    }

    @Override
    public Content.Chunk read()
    {
        if (failed != null)
            return Content.Chunk.from(failed, true);

        try (Content.Chunk readChunk = readChunk())
        {
            if (readChunk == null)
                return null;

            if (Content.Chunk.isFailure(readChunk))
                return readChunk;

            // The chunk fits, return it.
            if (readChunk.remaining() <= maxSize)
            {
                readChunk.retain();
                return readChunk;
            }

            // Store the chunk for later use.
            readChunk.retain();
            activeChunk = readChunk;

            try (RetainableByteBuffer buffer = readChunk.acquire())
            {
                try (RetainableByteBuffer slice = buffer.sliceAndConsume(maxSize))
                {
                    return Content.Chunk.from(slice, false);
                }
            }
        }
        catch (Throwable x)
        {
            fail(x);
            return Content.Chunk.from(failed, true);
        }
    }

    private Content.Chunk readChunk()
    {
        Content.Chunk chunk = activeChunk;
        activeChunk = null;
        if (chunk != null)
            return chunk;
        return delegate.read();
    }
}
