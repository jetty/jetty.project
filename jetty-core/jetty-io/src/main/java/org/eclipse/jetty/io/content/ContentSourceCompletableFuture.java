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
 *
 */
public abstract class ContentSourceCompletableFuture<X> extends CompletableFuture<X>
{
    private final Content.Source _content;

    public ContentSourceCompletableFuture(Content.Source content)
    {
        _content = content;
    }

    public CompletableFuture<X> parse()
    {
        onContentAvailable();
        return this;
    }

    private void onContentAvailable()
    {
        while (true)
        {
            Content.Chunk chunk = _content.read();
            if (chunk == null)
            {
                _content.demand(this::onContentAvailable);
                return;
            }
            if (Content.Chunk.isFailure(chunk))
            {
                if (!chunk.isLast() && ignoreTransientFailure(chunk.getFailure()))
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

    protected abstract X parse(Content.Chunk chunk);

    protected boolean ignoreTransientFailure(Throwable cause)
    {
        return false;
    }
}

