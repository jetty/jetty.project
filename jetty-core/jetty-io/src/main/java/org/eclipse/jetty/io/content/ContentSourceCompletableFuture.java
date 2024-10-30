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

import java.io.EOFException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.Invocable;

import static org.eclipse.jetty.util.thread.Invocable.combine;
import static org.eclipse.jetty.util.thread.Invocable.combineTypes;

/**
 * <p>A utility class to convert content from a {@link Content.Source} to an instance
 * available via a {@link java.util.concurrent.CompletableFuture}.</p>
 * <p>An example usage to asynchronously read UTF-8 content is:</p>
 * <pre>{@code
 * public static class CompletableUTF8String extends ContentSourceCompletableFuture<String>;
 * {
 *     private final Utf8StringBuilder builder = new Utf8StringBuilder();
 *
 *     public CompletableUTF8String(Content.Source content)
 *     {
 *         super(content);
 *     }
 *
 *     @Override
 *     protected String parse(Content.Chunk chunk) throws Throwable
 *     {
 *         // Accumulate the chunk bytes.
 *         if (chunk.hasRemaining())
 *             builder.append(chunk.getByteBuffer());
 *
 *         // Not the last chunk, the result is not ready yet.
 *         if (!chunk.isLast())
 *             return null;
 *
 *         // The result is ready.
 *         return builder.takeCompleteString(IllegalStateException::new);
 *     }
 * }
 * 
 * CompletableUTF8String cs = new CompletableUTF8String(source);
 * cs.parse();
 * String s = cs.get();
 * }</pre>
 */
public abstract class ContentSourceCompletableFuture<X> extends CompletableFuture<X> implements Invocable.Task
{
    private final Content.Source _content;
    private final InvocationType _invocationType;

    public ContentSourceCompletableFuture(Content.Source content)
    {
        this(content, InvocationType.NON_BLOCKING);
    }

    public ContentSourceCompletableFuture(Content.Source content, InvocationType invocationType)
    {
        _invocationType = Objects.requireNonNull(invocationType);
        if (_invocationType == InvocationType.EITHER)
            throw new IllegalArgumentException("EITHER is not supported");
        _content = content;
    }

    /**
     * <p>Initiates the parsing of the {@link Content.Source}.</p>
     * <p>For every valid chunk that is read, {@link #parse(Content.Chunk)}
     * is called, until a result is produced that is used to
     * complete this {@link java.util.concurrent.CompletableFuture}.</p>
     * <p>Internally, this method is called multiple times to progress
     * the parsing in response to {@link Content.Source#demand(Runnable)}
     * calls.</p>
     * <p>Exceptions thrown during parsing result in this
     * {@link java.util.concurrent.CompletableFuture} to be completed exceptionally.</p>
     */
    public void parse()
    {
        while (true)
        {
            Content.Chunk chunk = _content.read();
            if (chunk == null)
            {
                _content.demand(this);
                return;
            }
            if (Content.Chunk.isFailure(chunk))
            {
                if (chunk.isLast())
                {
                    completeExceptionally(chunk.getFailure());
                }
                else
                {
                    if (onTransientFailure(chunk.getFailure()))
                        continue;
                    _content.fail(chunk.getFailure());
                    completeExceptionally(chunk.getFailure());
                }
                return;
            }

            try
            {
                X x = parse(chunk);
                if (x != null)
                {
                    complete(x);
                    return;
                }
            }
            catch (Throwable failure)
            {
                completeExceptionally(failure);
                return;
            }
            finally
            {
                chunk.release();
            }

            if (chunk.isLast())
            {
                completeExceptionally(new EOFException());
                return;
            }
        }
    }

    /**
     * <p>Called by {@link #parse()} to parse a {@link org.eclipse.jetty.io.Content.Chunk}.</p>
     *
     * @param chunk The chunk containing content to parse. The chunk will never be {@code null} nor a
     *              {@link org.eclipse.jetty.io.Content.Chunk#isFailure(Content.Chunk) failure chunk}.
     *              If the chunk is stored away to be used later beyond the scope of this call,
     *              then implementations must call {@link Content.Chunk#retain()} and
     *              {@link Content.Chunk#release()} as appropriate.
     * @return The parsed {@code X} result instance or {@code null} if parsing is not yet complete
     * @throws Throwable If there is an error parsing
     */
    protected abstract X parse(Content.Chunk chunk) throws Throwable;

    /**
     * <p>Callback method that informs the parsing about how to handle transient failures.</p>
     *
     * @param cause A transient failure obtained by reading a {@link Content.Chunk#isLast() non-last}
     *             {@link org.eclipse.jetty.io.Content.Chunk#isFailure(Content.Chunk) failure chunk}
     * @return {@code true} if the transient failure can be ignored, {@code false} otherwise
     */
    protected boolean onTransientFailure(Throwable cause)
    {
        return false;
    }

    @Override
    public void run()
    {
        parse();
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> acceptEither(CompletionStage<? extends X> other, Consumer<? super X> action)
    {
        if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(action)))
            throw new IllegalStateException("Bad invocation type when not completed");

        return super.acceptEither(other, action);
    }

    @Override
    public <U> java.util.concurrent.CompletableFuture<U> applyToEither(CompletionStage<? extends X> other, Function<? super X, U> fn)
    {
        if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(fn)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.applyToEither(other, fn);
    }

    @Override
    public X get() throws InterruptedException, ExecutionException
    {
        if (getInvocationType() == InvocationType.BLOCKING && !isDone())
            throw new IllegalStateException("Must be NON_BLOCKING or completed");
        return super.get();
    }

    @Override
    public X get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        if (getInvocationType() == InvocationType.BLOCKING && !isDone())
            throw new IllegalStateException("Must be NON_BLOCKING or completed");
        return super.get(timeout, unit);
    }

    @Override
    public InvocationType getInvocationType()
    {
        return _invocationType;
    }

    @Override
    public <U> java.util.concurrent.CompletableFuture<U> handle(BiFunction<? super X, Throwable, ? extends U> fn)
    {
        if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(fn)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.handle(fn);
    }

    @Override
    public X join()
    {
        if (!isDone() && getInvocationType() == InvocationType.BLOCKING && !isDone())
            throw new IllegalStateException("Must be NON_BLOCKING or completed");
        return super.join();
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> runAfterBoth(CompletionStage<?> other, Runnable action)
    {
        if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(action)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.runAfterBoth(other, action);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> runAfterEither(CompletionStage<?> other, Runnable action)
    {
        if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(action)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.runAfterEither(other, action);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> thenAccept(Consumer<? super X> action)
    {
        if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(action)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.thenAccept(action);
    }

    @Override
    public <U> java.util.concurrent.CompletableFuture<Void> thenAcceptBoth(CompletionStage<? extends U> other, BiConsumer<? super X, ? super U> action)
    {
        if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(action)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.thenAcceptBoth(other, action);
    }

    @Override
    public <U> java.util.concurrent.CompletableFuture<U> thenApply(Function<? super X, ? extends U> fn)
    {
        if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(fn)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.thenApply(fn);
    }

    @Override
    public <U, V1> java.util.concurrent.CompletableFuture<V1> thenCombine(CompletionStage<? extends U> other, BiFunction<? super X, ? super U, ? extends V1> fn)
    {
        if (!isDone() && getInvocationType() != combineTypes(getInvocationType(), Invocable.getInvocationType(other), Invocable.getInvocationType(fn)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.thenCombine(other, fn);
    }

    @Override
    public <U> java.util.concurrent.CompletableFuture<U> thenCompose(Function<? super X, ? extends CompletionStage<U>> fn)
    {
        if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(fn)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.thenCompose(fn);
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> thenRun(Runnable action)
    {
        if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(action)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.thenRun(action);
    }

    @Override
    public java.util.concurrent.CompletableFuture<X> whenComplete(BiConsumer<? super X, ? super Throwable> action)
    {
        if (!isDone() && getInvocationType() != combine(getInvocationType(), Invocable.getInvocationType(action)))
            throw new IllegalStateException("Bad invocation type when not completed");
        return super.whenComplete(action);
    }
}

