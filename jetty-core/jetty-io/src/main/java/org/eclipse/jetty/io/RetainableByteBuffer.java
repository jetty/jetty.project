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

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;

/**
 * <p>A pooled ByteBuffer which maintains a reference count that is
 * incremented with {@link #retain()} and decremented with {@link #release()}. The buffer
 * is released to the pool when {@link #release()} is called one more time than {@link #retain()}.</p>
 * <p>A {@code RetainableByteBuffer} can either be:
 * <ul>
 *     <li>in pool; in this case {@link #isRetained()} returns {@code false} and calling {@link #release()} throws {@link IllegalStateException}</li>
 *     <li>out of pool but not retained; in this case {@link #isRetained()} returns {@code false} and calling {@link #release()} returns {@code true}</li>
 *     <li>out of pool and retained; in this case {@link #isRetained()} returns {@code true} and calling {@link #release()} returns {@code false}</li>
 * </ul>
 * <p>Calling {@link #release()} on a out of pool and retained instance does not re-pool it while that re-pools it on a out of pool but not retained instance.</p>
 */
public class RetainableByteBuffer implements Retainable
{
    private final ByteBuffer buffer;
    private final AtomicInteger references = new AtomicInteger();
    private final Consumer<ByteBuffer> releaser;
    private final AtomicLong lastUpdate = new AtomicLong(System.nanoTime());

    RetainableByteBuffer(ByteBuffer buffer, Consumer<ByteBuffer> releaser)
    {
        this.releaser = releaser;
        this.buffer = buffer;
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

    /**
     * Checks if {@link #retain()} has been called at least one more time than {@link #release()}.
     * @return true if this buffer is retained, false otherwise.
     */
    public boolean isRetained()
    {
        return references.get() > 1;
    }

    public boolean isDirect()
    {
        return buffer.isDirect();
    }

    /**
     * Increments the retained counter of this buffer. It must be done internally by
     * the pool right after creation and after each un-pooling.
     * The reason why this method exists on top of {@link #retain()} is to be able to
     * have some safety checks that must know why the ref counter is being incremented.
     */
    void acquire()
    {
        if (references.getAndUpdate(c -> c == 0 ? 1 : c) != 0)
            throw new IllegalStateException("re-pooled while still used " + this);
    }

    /**
     * Increments the retained counter of this buffer.
     */
    @Override
    public void retain()
    {
        if (references.getAndUpdate(c -> c == 0 ? 0 : c + 1) == 0)
            throw new IllegalStateException("released " + this);
    }

    /**
     * Decrements the retained counter of this buffer.
     * @return true if the buffer was re-pooled, false otherwise.
     */
    public boolean release()
    {
        int ref = references.updateAndGet(c ->
        {
            if (c == 0)
                throw new IllegalStateException("already released " + this);
            return c - 1;
        });
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
