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

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.Invocable;

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
public abstract class ContentSourceCompletableFuture<X> extends Invocable.InvocableCompletableFuture<X> implements Runnable
{
    private final Content.Source _content;

    public ContentSourceCompletableFuture(Content.Source content)
    {
        this(content, InvocationType.NON_BLOCKING);
    }

    public ContentSourceCompletableFuture(Content.Source content, InvocationType invocationType)
    {
        super(invocationType);
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

    @Override
    public void run()
    {
        parse();
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
}

