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

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ExceptionUtil;

public class MaxBufferContentSource implements Content.Source
{
    private final Content.Source delegate;
    private final int maxSize;
    private Content.Chunk activeChunk;
    private Throwable failed;
    private boolean terminated = false;

    public MaxBufferContentSource(Content.Source source, int maxSize)
    {
        this.delegate = source;
        this.maxSize = maxSize;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        if (activeChunk != null && activeChunk.hasRemaining())
            demandCallback.run();
        else
            delegate.demand(demandCallback);
    }

    @Override
    public void fail(Throwable failure)
    {
        freeActiveChunk();
        failed = ExceptionUtil.combine(failed, failure);
        delegate.fail(failure);
    }

    @Override
    public Content.Chunk read()
    {
        if (failed != null)
            return Content.Chunk.from(failed, true);

        if (terminated)
            return Content.Chunk.EOF;

        Content.Chunk readChunk = readChunk();
        if (readChunk == null)
            return null;

        if (Content.Chunk.isFailure(readChunk))
        {
            // TODO: avoid loop of exceptions?
            failed = ExceptionUtil.combine(failed, readChunk.getFailure());
            return readChunk;
        }

        try
        {
            if (readChunk.remaining() < maxSize)
            {
                // it fits, just return it.
                return readChunk;
            }
            else
            {
                boolean last = readChunk.isLast();
                ByteBuffer buf = BufferUtil.allocate(maxSize, readChunk.isDirect());
                buf.clear();
                int pos = readChunk.getByteBuffer().position();
                int len = BufferUtil.put(readChunk.getByteBuffer(), buf);
                readChunk.getByteBuffer().position(pos + len);
                buf.flip();

                if (last && readChunk.hasRemaining())
                    last = false; // still more to do.

                if (last)
                    freeActiveChunk(); // we are done with it now

                return Content.Chunk.from(buf, last);
            }
        }
        catch (Exception e)
        {
            fail(e);
            return Content.Chunk.from(failed, true);
        }
    }

    private void freeActiveChunk()
    {
        if (activeChunk != null)
            activeChunk.release();
        activeChunk = null;
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

        activeChunk = delegate.read();
        return activeChunk;
    }
}
