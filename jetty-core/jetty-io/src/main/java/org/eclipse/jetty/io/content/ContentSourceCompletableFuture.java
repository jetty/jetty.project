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
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content;

/**
 * A utility class to convert content from a {@link Content.Source} to an instance
 * available via a {@link CompletableFuture}.
 * <p>
 * An example usage to asynchronously read UTF-8 content is:
 * </p>
 * <pre>{@code
 *     public static class FutureUtf8String extends ContentSourceCompletableFuture<String>;
 *     {
 *         Utf8StringBuilder builder = new Utf8StringBuilder();
 *
 *         public FutureUtf8String(Content.Source content)
 *         {
 *             super(content);
 *         }
 *
 *         @Override
 *         protected String parse(Content.Chunk chunk) throws Throwable
 *         {
 *             if (chunk.hasRemaining())
 *                 builder.append(chunk.getByteBuffer());
 *             return chunk.isLast() ? builder.takeCompleteString(IllegalStateException::new) : null;
 *         }
 *     }
 *     ...
 *     new FutureUtf8String(source).thenAccept(System.err::println);
 * }</pre>
 */
public abstract class ContentSourceCompletableFuture<X> extends CompletableFuture<X>
{
    private final Content.Source _content;

    public ContentSourceCompletableFuture(Content.Source content)
    {
        _content = content;
    }

    /**
     * Progress the parsing, {@link Content.Source#read() reading} and/or {@link Content.Source#demand(Runnable) demanding}
     * as necessary.
     * <p>
     * This method must be called once to initiate the reading and parsing,
     * and is then called to progress parsing in response to any {@link Content.Source#demand(Runnable) demand} calls.
     * </p>
     */
    public void parse()
    {
        while (true)
        {
            Content.Chunk chunk = _content.read();
            if (chunk == null)
            {
                _content.demand(this::parse);
                return;
            }
            if (Content.Chunk.isFailure(chunk))
            {
                if (!chunk.isLast() && onTransientFailure(chunk.getFailure()))
                    continue;
                completeExceptionally(chunk.getFailure());
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
     * Called to parse a {@link org.eclipse.jetty.io.Content.Chunk}
     * @param chunk The chunk containing content to parse. The chunk will never be null nor a
     *              {@link org.eclipse.jetty.io.Content.Chunk#isFailure(Content.Chunk) failure chunk}.
     *              If references to the content of the chunk are to be held beyond the scope of this call,
     *              then implementations must call {@link Content.Chunk#retain()} and {@link Content.Chunk#release()}
     *              as appropriate.
     * @return The parsed {@code X} instance or null if parsing is not yet complete
     * @throws Throwable Thrown if there is an error parsing
     */
    protected abstract X parse(Content.Chunk chunk) throws Throwable;

    /**
     * @param cause A {@link Content.Chunk#isLast() non-last}
     *             {@link org.eclipse.jetty.io.Content.Chunk#isFailure(Content.Chunk) failure chunk}
     * @return True if the chunk can be ignored.
     */
    protected boolean onTransientFailure(Throwable cause)
    {
        return false;
    }
}

