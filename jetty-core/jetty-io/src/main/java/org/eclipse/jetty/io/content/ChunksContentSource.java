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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;

/**
 * <p>A {@link Content.Source} backed by one or more {@link Content.Chunk}s.</p>
 * <p>The chunks passed in the constructor are made available as chunks
 * via {@link #read()} and must be {@link Content.Chunk#release() released}
 * by the code that reads from objects of this class.</p>
 */
public class ChunksContentSource implements Content.Source
{
    private final AutoLock lock = new AutoLock();
    private final SerializedInvoker invoker = new SerializedInvoker(ChunksContentSource.class);
    private final long length;
    private final Collection<Content.Chunk> chunks;
    private Iterator<Content.Chunk> iterator;
    private Content.Chunk terminated;
    private Runnable demandCallback;

    public ChunksContentSource(Collection<Content.Chunk> chunks)
    {
        long sum = 0L;
        Iterator<Content.Chunk> it = chunks.iterator();
        while (it.hasNext())
        {
            Content.Chunk chunk = it.next();
            if (chunk != null)
            {
                if (it.hasNext() && chunk.isLast())
                    throw new IllegalArgumentException("Collection cannot contain a last Content.Chunk that is not at the last position: " + chunk);
                sum += chunk.getByteBuffer().remaining();
            }
        }
        // Only retain after the previous loop checked the collection is valid.
        chunks.stream().filter(Objects::nonNull).forEach(Content.Chunk::retain);

        this.chunks = chunks;
        this.length = sum;
    }

    public Collection<Content.Chunk> getChunks()
    {
        return chunks;
    }

    @Override
    public long getLength()
    {
        return length;
    }

    @Override
    public Content.Chunk read()
    {
        Content.Chunk chunk;
        try (AutoLock ignored = lock.lock())
        {
            if (terminated != null)
                return terminated;
            if (iterator == null)
                iterator = chunks.iterator();
            chunk = iterator.next();
            if (chunk != null && chunk.isLast())
                terminated = Content.Chunk.next(chunk);
            if (terminated == null && !iterator.hasNext())
                terminated = Content.Chunk.EOF;
        }
        return chunk;
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        try (AutoLock ignored = lock.lock())
        {
            if (this.demandCallback != null)
                throw new IllegalStateException("demand pending");
            this.demandCallback = demandCallback;
        }
        invoker.run(this::invokeDemandCallback);
    }

    private void invokeDemandCallback()
    {
        Runnable demandCallback;
        try (AutoLock ignored = lock.lock())
        {
            demandCallback = this.demandCallback;
            this.demandCallback = null;
        }
        if (demandCallback != null)
            ExceptionUtil.run(demandCallback, this::fail);
    }

    @Override
    public void fail(Throwable failure)
    {
        List<Content.Chunk> chunksToRelease;
        try (AutoLock ignored = lock.lock())
        {
            if (terminated != null)
                return;
            terminated = Content.Chunk.from(failure);
            if (iterator != null)
            {
                chunksToRelease = new ArrayList<>();
                iterator.forEachRemaining(chunksToRelease::add);
            }
            else
            {
                chunksToRelease = List.copyOf(chunks);
            }
        }
        chunksToRelease.stream().filter(Objects::nonNull).forEach(Content.Chunk::release);
    }
}
