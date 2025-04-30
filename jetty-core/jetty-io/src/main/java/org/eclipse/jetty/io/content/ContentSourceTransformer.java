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

import java.nio.channels.ReadPendingException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.TypeUtil;
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

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final Content.Source rawSource;
    private volatile Content.Chunk rawChunk;
    private volatile boolean needsRawRead;

    protected ContentSourceTransformer(Content.Source rawSource)
    {
        this.rawSource = rawSource;
        this.needsRawRead = true;
    }

    protected Content.Source getContentSource()
    {
        return rawSource;
    }

    private Content.Chunk beforeRead()
    {
        while (true)
        {
            State current = state.get();
            switch (current.type)
            {
                case IDLE ->
                {
                    if (state.compareAndSet(current, State.READING))
                        return null;
                }
                case READING -> throw new ReadPendingException();
                case EOF ->
                {
                    return Content.Chunk.EOF;
                }
                case FAILING -> throw new IllegalStateException();
                case FAILED ->
                {
                    return ((State.Failed)current).chunk;
                }
            }
        }
    }

    @Override
    public Content.Chunk read()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Reading {}", this);

        Content.Chunk chunk = beforeRead();
        if (chunk != null)
            return chunk;

        while (true)
        {
            if (needsRawRead)
            {
                rawChunk = rawSource.read();
                if (LOG.isDebugEnabled())
                    LOG.debug("Raw chunk {} {}", rawChunk, this);
                needsRawRead = rawChunk == null;
                if (rawChunk == null)
                    return afterRead(State.Type.IDLE, null);
            }

            if (Content.Chunk.isFailure(rawChunk))
            {
                Content.Chunk failureChunk = rawChunk;
                Content.Chunk nextChunk = Content.Chunk.next(failureChunk);
                needsRawRead = nextChunk == null;
                afterRead(nextChunk == null ? State.Type.IDLE : State.Type.FAILED, nextChunk);
                return failureChunk;
            }

            boolean rawLast = rawChunk != null && rawChunk.isLast();

            Content.Chunk transformedChunk = process(rawChunk != null ? rawChunk : Content.Chunk.EMPTY);
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

                if (transformedFailure && transformedLast)
                    return afterRead(State.Type.FAILED, transformedChunk);

                if (rawLast && transformedLast)
                    return afterRead(State.Type.EOF, transformedChunk);

                return afterRead(State.Type.IDLE, transformedChunk);
            }

            needsRawRead = rawChunk == null;
        }
    }

    private Content.Chunk afterRead(State.Type targetType, Content.Chunk chunk)
    {
        while (true)
        {
            State current = state.get();
            switch (current.type)
            {
                case IDLE, EOF, FAILED -> throw new IllegalStateException();
                case READING ->
                {
                    switch (targetType)
                    {
                        case IDLE ->
                        {
                            if (state.compareAndSet(current, State.IDLE))
                                return chunk;
                        }
                        case FAILED ->
                        {
                            if (state.compareAndSet(current, new State.Failed(chunk)))
                            {
                                dispose(chunk.getFailure());
                                return chunk;
                            }
                        }
                        case EOF ->
                        {
                            if (state.compareAndSet(current, State.EOF))
                            {
                                release();
                                return chunk;
                            }
                        }
                        default -> throw new IllegalStateException();
                    }
                }
                case FAILING ->
                {
                    Content.Chunk failedChunk = ((State.Failing)current).chunk;
                    Throwable failure = failedChunk.getFailure();
                    if (Content.Chunk.isFailure(chunk))
                        ExceptionUtil.addSuppressedIfNotAssociated(failure, chunk.getFailure());
                    if (state.compareAndSet(current, new State.Failed(failedChunk)))
                    {
                        dispose(failure);
                        return chunk;
                    }
                }
            }
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

        while (true)
        {
            State current = state.get();
            switch (current.type)
            {
                case IDLE ->
                {
                    if (state.compareAndSet(current, new State.Failed(Content.Chunk.from(failure, true))))
                    {
                        dispose(failure);
                        return;
                    }
                }
                case READING ->
                {
                    if (state.compareAndSet(current, new State.Failing(Content.Chunk.from(failure, true))))
                        return;
                }
                default ->
                {
                    return;
                }
            }
        }
    }

    private void dispose(Throwable failure)
    {
        rawSource.fail(failure);
        needsRawRead = false;
        if (rawChunk != null)
            rawChunk.release();
        rawChunk = Content.Chunk.from(failure, true);
        release();
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

    /**
     * @return whether the transformation is complete, either successfully or with a failure.
     */
    public boolean isComplete()
    {
        return switch (state.get().type)
        {
            case EOF, FAILED -> true;
            default -> false;
        };
    }

    @Override
    public String toString()
    {
        return "%s@%x[state=%s,source=%s]".formatted(
            TypeUtil.toShortName(getClass()),
            hashCode(),
            state.get(),
            rawSource
        );
    }

    /**
     * <p>State transitions are:</p>
     * <p>IDLE -> FAILED, when {@link #fail(Throwable)} is called</p>
     * <p>IDLE -> READING, when {@link #read()} is called</p>
     * <p>READING -> IDLE, when {@link #read()} returns a non-last chunk</p>
     * <p>READING -> EOF, when {@link #read()} returns a last chunk</p>
     * <p>READING -> FAILED, when reading from the raw source returns a failure chunk</p>
     * <p>READING -> FAILING, when a concurrent call to {@link #fail(Throwable)} happens during a read</p>
     * <p>FAILING -> FAILED, when just before returning, {@link #read()} detects a concurrent call to {@link #fail(Throwable)}</p>
     *
     */
    private static sealed class State
    {
        private static final State IDLE = new Idle();
        private static final State READING = new Reading();
        private static final State EOF = new EOF();

        private final Type type;

        private State(Type type)
        {
            this.type = type;
        }

        private static final class Idle extends State
        {
            private Idle()
            {
                super(Type.IDLE);
            }
        }

        private static final class Reading extends State
        {
            private Reading()
            {
                super(Type.READING);
            }
        }

        private static final class EOF extends State
        {
            private EOF()
            {
                super(Type.EOF);
            }
        }

        private static final class Failing extends State
        {
            private final Content.Chunk chunk;

            private Failing(Content.Chunk chunk)
            {
                super(Type.FAILING);
                this.chunk = chunk;
            }
        }

        private static final class Failed extends State
        {
            private final Content.Chunk chunk;

            private Failed(Content.Chunk chunk)
            {
                super(Type.FAILED);
                this.chunk = chunk;
            }
        }

        private enum Type
        {
            IDLE, READING, EOF, FAILING, FAILED
        }
    }
}
