//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io.content;

import java.util.Objects;

import org.eclipse.jetty.io.Content;

public abstract class ContentSourceTransformer implements Content.Source
{
    private final Content.Source rawSource;
    private Content.Chunk rawChunk;
    private Content.Chunk transformedChunk;
    private boolean needsRawRead;
    private Runnable demandCallback;

    public ContentSourceTransformer(Content.Source rawSource)
    {
        this.rawSource = rawSource;
    }

    @Override
    public Content.Chunk read()
    {
        while (true)
        {
            if (needsRawRead)
            {
                rawChunk = rawSource.read();
                needsRawRead = rawChunk == null;
                if (rawChunk == null)
                    return null;
            }

            if (rawChunk instanceof Content.Chunk.Error)
                return rawChunk;

            if (transformedChunk instanceof Content.Chunk.Error)
                return transformedChunk;

            transformedChunk = process(rawChunk);

            // Release of rawChunk must be done by transform().
            rawChunk = null;

            if (transformedChunk != null)
            {
                Content.Chunk result = transformedChunk;
                transformedChunk = Content.Chunk.next(result);
                return result;
            }

            needsRawRead = true;
        }
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        this.demandCallback = Objects.requireNonNull(demandCallback);
        rawSource.demand(this::onRawAvailable);
    }

    @Override
    public void fail(Throwable failure)
    {
        rawSource.fail(failure);
    }

    private void onRawAvailable()
    {
        Runnable demandCallback = this.demandCallback;
        this.demandCallback = null;
        runDemandCallback(demandCallback);
    }

    private void runDemandCallback(Runnable demandCallback)
    {
        try
        {
            demandCallback.run();
        }
        catch (Throwable x)
        {
            fail(x);
        }
    }

    private Content.Chunk process(Content.Chunk rawChunk)
    {
        try
        {
            return transform(rawChunk);
        }
        catch (Throwable x)
        {
            if (rawChunk != null)
                rawChunk.release();
            fail(x);
            return Content.Chunk.from(x);
        }
    }

    protected abstract Content.Chunk transform(Content.Chunk rawChunk);
}
