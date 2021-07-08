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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;

/**
 * A Retainable ByteBuffer.
 * <p>A ByteBuffer which maintains a reference count that is
 * initially 1, incremented with {@link #retain()} and decremented with {@link #release()}. The buffer
 * is released to the pool when the reference count is decremented to 0.</p>
 */
public class RetainableByteBuffer implements Retainable
{
    private final ByteBuffer buffer;
    private final AtomicInteger references;
    private final Consumer<ByteBuffer> releaser;
    private final AtomicLong lastUpdate = new AtomicLong(System.nanoTime());

    RetainableByteBuffer(ByteBuffer buffer, Consumer<ByteBuffer> releaser)
    {
        this.releaser = releaser;
        this.buffer = buffer;
        this.references = new AtomicInteger(1);
    }

    public int capacity()
    {
        return buffer.capacity();
    }

    public ByteBuffer getBuffer()
    {
        return buffer;
    }

    public long getLastUpdate()
    {
        return lastUpdate.getOpaque();
    }

    public boolean isRetained()
    {
        return references.get() > 1;
    }

    public boolean isDirect()
    {
        return buffer.isDirect();
    }

    @Override
    public void retain()
    {
        references.incrementAndGet();
    }

    public boolean release()
    {
        int ref = references.decrementAndGet();
        if (ref < 0)
        {
            references.incrementAndGet();
            throw new IllegalStateException("already released " + this);
        }
        if (ref == 0)
        {
            lastUpdate.setOpaque(System.nanoTime());
            releaser.accept(buffer);
            return true;
        }
        return false;
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
        return String.format("%s@%x{%s,r=%d}", getClass().getSimpleName(), hashCode(), BufferUtil.toDetailString(buffer), references.get());
    }
}
