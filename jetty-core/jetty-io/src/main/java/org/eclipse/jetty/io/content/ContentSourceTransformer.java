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

package org.eclipse.jetty.io.content;

import java.util.Objects;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.SerializedInvoker;

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
    private final SerializedInvoker invoker;
    private final Content.Source rawSource;
    private Content.Chunk rawChunk;
    private Content.Chunk transformedChunk;
    private volatile boolean needsRawRead;
    private volatile Runnable demandCallback;

    protected ContentSourceTransformer(Content.Source rawSource)
    {
        this(rawSource, new SerializedInvoker());
    }

    protected ContentSourceTransformer(Content.Source rawSource, SerializedInvoker invoker)
    {
        this.rawSource = rawSource;
        this.invoker = invoker;
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

            if (Content.Chunk.isError(rawChunk))
                return rawChunk;

            if (Content.Chunk.isError(transformedChunk))
                return transformedChunk;

            transformedChunk = process(rawChunk);

            if (rawChunk != null && rawChunk != transformedChunk)
                rawChunk.release();
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
        if (needsRawRead)
            rawSource.demand(() -> invoker.run(this::invokeDemandCallback));
        else
            invoker.run(this::invokeDemandCallback);
    }

    @Override
    public void fail(Throwable failure)
    {
        rawSource.fail(failure);
    }

    private void invokeDemandCallback()
    {
        Runnable demandCallback = this.demandCallback;
        this.demandCallback = null;
        if (demandCallback != null)
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
     * <p>Implementations should return an {@link Content.Chunk} with non-null
     * {@link Content.Chunk#getFailure()} in case
     * of transformation errors.</p>
     * <p>Exceptions thrown by this method are equivalent to returning an error chunk.</p>
     * <p>Implementations of this method may return:</p>
     * <ul>
     * <li>{@code null}, if more input chunks are necessary to produce an output chunk</li>
     * <li>the {@code inputChunk} itself, typically in case of non-null {@link Content.Chunk#getFailure()},
     * or when no transformation is required</li>
     * <li>a new {@link Content.Chunk} derived from {@code inputChunk}.</li>
     * </ul>
     *
     * @param inputChunk a chunk read from the wrapped {@link Content.Source}
     * @return a transformed chunk or {@code null}
     */
    protected abstract Content.Chunk transform(Content.Chunk inputChunk);
}
