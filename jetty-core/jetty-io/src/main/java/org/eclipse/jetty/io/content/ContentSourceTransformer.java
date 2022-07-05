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

/**
 * An abstract transformer of {@link Content.Chunk}s from a {@link Content.Source} as a {@link Content.Source}.
 * <p>
 * This abstract {@link Content.Source} wraps another {@link Content.Source} and implementors need only to provide
 * the {@link #transform(Content.Chunk)} method, which is used to transform {@link Content.Chunk} read from the
 * wrapped source.
 * <p>
 * The {@link #demand(Runnable)} conversation is passed directly to the wrapped {@link Content.Source}, which means
 * that transformations that may fully consume bytes read can result in a null return from {@link Content.Source#read()}
 * even after a callback to the demand {@link Runnable} (as per spurious invocation in {@link Content.Source#demand(Runnable)}.
 */
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

    /**
     * Content chunk transformation method.
     * <p>
     * This method is called during a {@link Content.Source#read()} to transform a raw chunk to a chunk that
     * will be returned from the read call.  The caller of {@link Content.Source#read()} method is always
     * responsible for calling {@link Content.Chunk#release()} on the returned chunk, which may be:
     * <ul>
     * <li>the <code>rawChunk</code>. This is typically done for {@link Content.Chunk.Error}s,
     *     when {@link Content.Chunk#isLast()} is true, or if no transformation is required.</li>
     * <li>a new (or predefined) {@link Content.Chunk} derived from the <code>rawChunk</code>. The transform is
     *     responsible for calling {@link Content.Chunk#release()} on the <code>rawChunk</code>, either during the call
     *     to {@link Content.Source#read()} or subsequently.</li>
     * <li>null if the <code>rawChunk</code> is fully consumed and/or requires additional chunks to be transformed.</li>
     * </ul>
     * @param rawChunk A chunk read from the wrapped {@link Content.Source}. It is always non null.
     * @return The transformed chunk or null.
     */
    protected abstract Content.Chunk transform(Content.Chunk rawChunk);
}
