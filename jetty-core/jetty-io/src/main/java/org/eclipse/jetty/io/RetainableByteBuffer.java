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
public class RetainableByteBuffer extends Retainable.ReferenceCounter
{
    private final ByteBuffer buffer;
    private final Consumer<RetainableByteBuffer> releaser;
    private final AtomicLong lastUpdate = new AtomicLong(System.nanoTime());

    RetainableByteBuffer(ByteBuffer buffer, Consumer<RetainableByteBuffer> releaser)
    {
        super(0);
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

    public boolean isDirect()
    {
        return buffer.isDirect();
    }

    protected void acquire()
    {
        // Overridden for visibility.
        super.acquire();
    }

    public boolean release()
    {
        boolean released = super.release();
        if (released)
        {
            lastUpdate.setOpaque(System.nanoTime());
            releaser.accept(this);
        }
        return released;
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
        return "%s[%s]".formatted(super.toString(), BufferUtil.toDetailString(buffer));
    }
}
