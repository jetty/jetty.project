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

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.SerializedInvoker;

/**
 * <p>A {@link Content.Source} backed by one or more {@link ByteBuffer}s.
 * The buffers passed in the constructor are made available as {@link Content.Chunk}s
 * via {@link #read()}. Any calls to {@link #demand(Runnable)} are immediately satisfied.</p>
 */
public class ByteBufferContentSource implements Content.Source
{
    private final AutoLock lock = new AutoLock();
    private final SerializedInvoker invoker = new SerializedInvoker();
    private final long length;
    private final Collection<ByteBuffer> byteBuffers;
    private Iterator<ByteBuffer> iterator;
    private Content.Chunk terminated;
    private Runnable demandCallback;

    public ByteBufferContentSource(ByteBuffer... byteBuffers)
    {
        this(List.of(byteBuffers));
    }

    public ByteBufferContentSource(Collection<ByteBuffer> byteBuffers)
    {
        this.byteBuffers = byteBuffers;
        this.length = byteBuffers.stream().mapToLong(Buffer::remaining).sum();
    }

    public Collection<ByteBuffer> getByteBuffers()
    {
        return byteBuffers;
    }

    @Override
    public long getLength()
    {
        return length;
    }

    @Override
    public Content.Chunk read()
    {
        ByteBuffer buffer;
        boolean last;
        try (AutoLock ignored = lock.lock())
        {
            if (terminated != null)
                return terminated;
            if (iterator == null)
                iterator = byteBuffers.iterator();
            if (!iterator.hasNext())
                return terminated = Content.Chunk.EOF;
            buffer = iterator.next().slice();
            last = !iterator.hasNext();
        }
        return Content.Chunk.from(buffer, last);
    }

    protected boolean rewind()
    {
        try (AutoLock ignored = lock.lock())
        {
            iterator = null;
            terminated = null;
            demandCallback = null;
            return true;
        }
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
        try (AutoLock ignored = lock.lock())
        {
            if (terminated != null)
                return;
            terminated = Content.Chunk.from(failure);
        }
    }
}
