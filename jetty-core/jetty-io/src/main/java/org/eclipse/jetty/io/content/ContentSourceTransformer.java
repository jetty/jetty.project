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

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.ExceptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This abstract {@link Content.Source} wraps another {@link Content.Source} and implementers need only
 * to implement the {@link #transform(Content.Chunk)} method, which is used to transform {@link Content.Chunk}
 * read from the wrapped source.</p>
 * <p>The {@link #demand(Runnable)} conversation is passed directly to the wrapped {@link Content.Source},
 * which means that transformations that may fully consume bytes read can result in a null return from
 * {@link Content.Source#read()} even after a callback to the demand {@link Runnable}, as per spurious
 * invocation in {@link Content.Source#demand(Runnable)}.</p>
 */
public abstract class ContentSourceTransformer implements Content.Source
{
    private static final Logger LOG = LoggerFactory.getLogger(ContentSourceTransformer.class);

    private final Content.Source rawSource;
    private Content.Chunk rawChunk;
    private Content.Chunk transformedChunk;
    private volatile boolean needsRawRead;
    private boolean finished;

    protected ContentSourceTransformer(Content.Source rawSource)
    {
        this.rawSource = rawSource;
        this.needsRawRead = true;
    }

    protected Content.Source getContentSource()
    {
        return rawSource;
    }

    @Override
    public Content.Chunk read()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Reading {}", this);

        while (true)
        {
            if (needsRawRead)
            {
                rawChunk = rawSource.read();
                if (LOG.isDebugEnabled())
                    LOG.debug("Raw chunk {} {}", rawChunk, this);
                needsRawRead = rawChunk == null;
                if (rawChunk == null)
                    return null;
            }

            if (Content.Chunk.isFailure(rawChunk))
            {
                Content.Chunk failure = rawChunk;
                rawChunk = Content.Chunk.next(rawChunk);
                needsRawRead = rawChunk == null;
                if (rawChunk != null)
                {
                    finished = true;
                    release();
                }
                return failure;
            }

            if (Content.Chunk.isFailure(transformedChunk))
                return transformedChunk;

            if (finished)
                return Content.Chunk.EOF;

            boolean rawLast = rawChunk != null && rawChunk.isLast();

            transformedChunk = process(rawChunk != null ? rawChunk : Content.Chunk.EMPTY);
            if (LOG.isDebugEnabled())
                LOG.debug("Transformed chunk {} {}", transformedChunk, this);

            if (rawChunk == null && (transformedChunk == null || transformedChunk == Content.Chunk.EMPTY))
            {
                needsRawRead = true;
                continue;
            }

            // Prevent double release.
            if (transformedChunk == rawChunk)
                rawChunk = null;

            if (rawChunk != null && rawChunk.isEmpty())
            {
                rawChunk.release();
                rawChunk = Content.Chunk.next(rawChunk);
            }

            if (transformedChunk != null)
            {
                boolean transformedLast = transformedChunk.isLast();
                boolean transformedFailure = Content.Chunk.isFailure(transformedChunk);

                // Transformation may be complete, but rawSource is not read until EOF,
                // change to non-last transformed chunk to force more read() and transform().
                if (transformedLast && !transformedFailure && !rawLast)
                {
                    if (transformedChunk.isEmpty())
                        transformedChunk = Content.Chunk.EMPTY;
                    else
                        transformedChunk = Content.Chunk.asChunk(transformedChunk.getByteBuffer(), false, transformedChunk);
                }

                boolean terminated = rawLast && transformedLast;
                boolean terminalFailure = transformedFailure && transformedLast;

                Content.Chunk result = transformedChunk;
                transformedChunk = Content.Chunk.next(result);

                if (terminated || terminalFailure)
                {
                    finished = true;
                    release();
                }

                return result;
            }

            needsRawRead = rawChunk == null;
        }
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Demanding {} {}", demandCallback, this);

        if (needsRawRead)
            rawSource.demand(demandCallback);
        else
            ExceptionUtil.run(demandCallback, this::fail);
    }

    @Override
    public void fail(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Failing {}", this, failure);
        rawSource.fail(failure);
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
     * it is subsequently invoked with either the input chunk (if it has
     * remaining bytes), or with {@link Content.Chunk#EMPTY} to try to
     * produce more output chunks from the previous input chunk.
     * For example, a single compressed input chunk may be transformed into
     * multiple uncompressed output chunks.</p>
     * <p>The input chunk is released as soon as this method returns if it
     * is fully consumed, so implementations that must hold onto the input
     * chunk must arrange to call {@link Content.Chunk#retain()} and its
     * correspondent {@link Content.Chunk#release()}.</p>
     * <p>Implementations should return a {@link Content.Chunk} with non-null
     * {@link Content.Chunk#getFailure()} in case of transformation errors.</p>
     * <p>Exceptions thrown by this method are equivalent to returning an
     * error chunk.</p>
     * <p>Implementations of this method may return:</p>
     * <ul>
     * <li>{@code null} or {@link Content.Chunk#EMPTY}, if more input chunks
     * are necessary to produce an output chunk</li>
     * <li>the {@code inputChunk} itself, typically in case of non-null
     * {@link Content.Chunk#getFailure()}, or when no transformation is required</li>
     * <li>a new {@link Content.Chunk} derived from {@code inputChunk}.</li>
     * </ul>
     * <p>The input chunk should be consumed (its position updated) as the
     * transformation proceeds.</p>
     *
     * @param inputChunk a chunk read from the wrapped {@link Content.Source}
     * @return a transformed chunk or {@code null}
     */
    protected abstract Content.Chunk transform(Content.Chunk inputChunk);

    /**
     * <p>Invoked when the transformation is complete to release any resource.</p>
     */
    protected void release()
    {
    }

    @Override
    public String toString()
    {
        return "%s@%x[finished=%b,source=%s]".formatted(
            getClass().getSimpleName(),
            hashCode(),
            finished,
            rawSource
        );
    }
}
