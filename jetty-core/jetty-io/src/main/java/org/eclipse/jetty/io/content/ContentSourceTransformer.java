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
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This abstract {@link Content.Source} wraps another {@link Content.Source} and implementers need only
 * to implement the {@link #transform(Content.Chunk)} method, which is used to transform {@link Content.Chunk}
 * read from the wrapped source.</p>
 * <p>The {@link #demand(Runnable)} conversation is passed directly to the wrapped {@link Content.Source},
 * which means that transformations that may fully consume bytes read can result in a null return from
 * {@link Content.Source#read()} even after a callback to the demand {@link Runnable} (as per spurious
 * invocation in {@link Content.Source#demand(Runnable)}.</p>
 */
public abstract class ContentSourceTransformer implements Content.Source
{
    private static final Logger LOG = LoggerFactory.getLogger(ContentSourceTransformer.class);

    private final SerializedInvoker invoker = new SerializedInvoker();
    private final Content.Source rawSource;
    private Content.Chunk rawChunk;
    private Content.Chunk transformedChunk;
    private boolean needsRawRead = true;
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
                if (LOG.isDebugEnabled())
                    LOG.debug("performing raw read");
                rawChunk = rawSource.read();
                needsRawRead = rawChunk == null;
                if (LOG.isDebugEnabled())
                    LOG.debug("still need raw read? {}", needsRawRead);
                if (rawChunk == null)
                    return null;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("raw chunk: {}", rawChunk);

            if (rawChunk instanceof Content.Chunk.Error)
                return rawChunk;

            if (LOG.isDebugEnabled())
                LOG.debug("transformed chunk: {}", transformedChunk);

            if (transformedChunk instanceof Content.Chunk.Error)
                return transformedChunk;

            if (LOG.isDebugEnabled())
                LOG.debug("need transformation");
            transformedChunk = process(rawChunk);
            if (LOG.isDebugEnabled())
                LOG.debug("transformed chunk: {}, raw chunk: {}", transformedChunk, rawChunk);

            if (rawChunk != null && rawChunk != transformedChunk)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("releasing raw chunk");
                rawChunk.release();
            }
            rawChunk = null;

            if (transformedChunk != null)
            {
                Content.Chunk result = transformedChunk;
                transformedChunk = Content.Chunk.next(result);
                if (LOG.isDebugEnabled())
                    LOG.debug("returning {}, transformed chunk now is: {}", result, transformedChunk);
                return result;
            }

            needsRawRead = true;
            if (LOG.isDebugEnabled())
                LOG.debug("setting need for raw read");
        }
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        this.demandCallback = Objects.requireNonNull(demandCallback);
        if (!needsRawRead)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("demand, serving immediately");
            invoker.run(this::onRawAvailable);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("demand, delegating to raw source");
            rawSource.demand(this::onRawAvailable);
        }
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
            fail(x);
            return Content.Chunk.from(x);
        }
    }

    /**
     * <p>Transforms the input chunk parameter into an output chunk.</p>
     * <p>When this method produces a non-{@code null}, non-last chunk,
     * it is subsequently invoked with a {@code null} input chunk to try to
     * produce more output chunks from the previous input chunk.
     * For example, a single compressed input chunk may be transformed into
     * multiple uncompressed output chunks.</p>
     * <p>The input chunk is released as soon as this method returns, so
     * implementations that must hold onto the input chunk must arrange to call
     * {@link Content.Chunk#retain()} and its correspondent {@link Content.Chunk#release()}.</p>
     * <p>Implementations should return an {@link Content.Chunk.Error error chunk} in case
     * of transformation errors.</p>
     * <p>Exceptions thrown by this method are equivalent to returning an error chunk.</p>
     * <p>Implementations of this method may return:</p>
     * <ul>
     * <li>{@code null}, if more input chunks are necessary to produce an output chunk</li>
     * <li>the {@code inputChunk} itself, typically in case of {@link Content.Chunk.Error}s,
     * or when no transformation is required</li>
     * <li>a new {@link Content.Chunk} derived from {@code inputChunk}.</li>
     * </ul>
     *
     * @param inputChunk a chunk read from the wrapped {@link Content.Source}
     * @return a transformed chunk or {@code null}
     */
    protected abstract Content.Chunk transform(Content.Chunk inputChunk);
}
