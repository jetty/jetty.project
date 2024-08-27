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

import java.io.IOException;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.ExceptionUtil;

public abstract class DecoderSource implements Content.Source
{
    private final Content.Source source;
    private Content.Chunk activeChunk;
    private Throwable failed;
    private boolean terminated = false;

    protected DecoderSource(Content.Source source)
    {
        this.source = source;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        if (activeChunk != null && activeChunk.hasRemaining())
            demandCallback.run();
        else
            source.demand(demandCallback);
    }

    @Override
    public void fail(Throwable failure)
    {
        failed = ExceptionUtil.combine(failed, failure);
        source.fail(failure);
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
            Content.Chunk chunk = nextChunk(readChunk);
            if (chunk != null && chunk.isLast())
            {
                terminate();
            }
            return chunk;
        }
        catch (Exception e)
        {
            fail(e);
            return Content.Chunk.from(failed, true);
        }
    }

    /**
     * Process the readChunk and produce a response Chunk.
     *
     * @param readChunk the active Read Chunk (never null, never a failure)
     * @throws IOException if decoder failure occurs.
     */
    protected abstract Content.Chunk nextChunk(Content.Chunk readChunk) throws IOException;

    /**
     * Place to cleanup and release any resources
     * being held by this DecoderSource.
     */
    protected void release()
    {
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

        activeChunk = source.read();
        return activeChunk;
    }

    private void terminate()
    {
        if (!terminated)
        {
            terminated = true;
            freeActiveChunk();
            release();
        }
    }
}
