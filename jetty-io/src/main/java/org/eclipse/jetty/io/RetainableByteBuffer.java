//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.Retainable;

/**
 * A Retainable ByteBuffer.
 * <p>Acquires a ByteBuffer from a {@link ByteBufferPool} and maintains a reference count that is
 * initially 1, incremented with {@link #retain()} and decremented with {@link #release()}. The buffer
 * is released to the pool when the reference count is decremented to 0.</p>
 */
public class RetainableByteBuffer implements Retainable, Attachable
{
    private final ByteBufferPool pool;
    private final ByteBuffer buffer;
    private final AtomicInteger references;
    private Pool<RetainableByteBuffer>.Entry entry;

    protected RetainableByteBuffer(int size, boolean direct)
    {
        this(null, size, direct);
    }

    RetainableByteBuffer(ByteBufferPool pool, int size, boolean direct)
    {
        this.pool = pool;
        if (pool != null)
        {
            this.buffer = pool.acquire(size, direct);
        }
        else
        {
            this.buffer = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
            BufferUtil.clear(this.buffer);
        }
        this.references = new AtomicInteger(1);
    }

    public int capacity()
    {
        return buffer.capacity();
    }

    @Override
    public Object getAttachment()
    {
        return entry;
    }

    @Override
    public void setAttachment(Object attachment)
    {
        @SuppressWarnings("unchecked")
        Pool<RetainableByteBuffer>.Entry entry = (Pool<RetainableByteBuffer>.Entry)attachment;
        this.entry = entry;
    }

    public ByteBuffer getBuffer()
    {
        return buffer;
    }

    public int getReferences()
    {
        return references.get();
    }

    public boolean isDirect()
    {
        return buffer.isDirect();
    }

    @Override
    public void retain()
    {
        while (true)
        {
            int r = references.get();
            if (r == 0 && pool != null)
                throw new IllegalStateException("released " + this);
            if (references.compareAndSet(r, r + 1))
                break;
        }
    }

    public int release()
    {
        int ref = references.decrementAndGet();
        if (ref == 0)
            if (pool != null)
            {
                pool.release(buffer);
            }
            else
            {
                BufferUtil.clear(buffer);
                if (entry != null)
                    entry.release();
            }
        else if (ref < 0)
            throw new IllegalStateException("already released " + this);
        return ref;
    }

    public int remaining()
    {
        return buffer.remaining();
    }

    public boolean hasRemaining()
    {
        return remaining() > 0;
    }

    public boolean isEmpty()
    {
        return !hasRemaining();
    }

    public void clear()
    {
        BufferUtil.clear(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,r=%d}", getClass().getSimpleName(), hashCode(), BufferUtil.toDetailString(buffer), getReferences());
    }
}
