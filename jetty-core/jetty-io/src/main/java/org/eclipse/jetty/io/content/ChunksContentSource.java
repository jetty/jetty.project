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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.io.Content;
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
    private final SerializedInvoker invoker = new SerializedInvoker();
    private final long length;
    private final Collection<Content.Chunk> chunks;
    private Iterator<Content.Chunk> iterator;
    private Content.Chunk terminated;
    private Runnable demandCallback;

    public ChunksContentSource(Collection<Content.Chunk> chunks)
    {
        this.chunks = chunks;
        this.length = chunks.stream().mapToLong(c -> c.getByteBuffer().remaining()).sum();
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
        boolean last;
        try (AutoLock ignored = lock.lock())
        {
            if (terminated != null)
                return terminated;
            if (iterator == null)
                iterator = chunks.iterator();
            if (!iterator.hasNext())
                return terminated = Content.Chunk.EOF;
            chunk = iterator.next();
            last = !iterator.hasNext();
            if (last)
                terminated = Content.Chunk.EOF;
        }
        return Content.Chunk.from(chunk.getByteBuffer().slice(), chunk.isLast(), chunk);
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

    @Override
    public void fail(Throwable failure)
    {
        List<Content.Chunk> toFail = List.of();
        try (AutoLock ignored = lock.lock())
        {
            if (terminated != null)
                return;
            terminated = Content.Chunk.from(failure);
            if (iterator != null)
            {
                toFail = new ArrayList<>();
                iterator.forEachRemaining(toFail::add);
            }
        }
        toFail.forEach(Content.Chunk::release);
    }
}
