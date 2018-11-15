//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Retainable ByteBuffer.
 * <p>Acquires a ByteBuffer from a {@link ByteBufferPool} and maintains a reference count that is
 * initially 1, incremented with {@link #retain()} and decremented with {@link #release()}. The buffer
 * is released to the pool when the reference count is decremented to 0.</p>
 */
public class RetainableByteBuffer implements Retainable
{
    private final ByteBufferPool pool;
    private final ByteBuffer buffer;
    private final AtomicInteger references;

    public RetainableByteBuffer(ByteBufferPool pool, int size)
    {
        this(pool, size, false);
    }

    public RetainableByteBuffer(ByteBufferPool pool, int size, boolean direct)
    {
        this.pool = pool;
        this.buffer = pool.acquire(size, direct);
        this.references = new AtomicInteger(1);
    }

    public ByteBuffer getBuffer()
    {
        return buffer;
    }

    public int getReferences()
    {
        return references.get();
    }

    @Override
    public void retain()
    {
        while (true)
        {
            int r = references.get();
            if (r == 0)
                throw new IllegalStateException("released");
            if (references.compareAndSet(r, r + 1))
                break;
        }
    }

    public int release()
    {
        int ref = references.decrementAndGet();
        if (ref == 0)
            pool.release(buffer);
        else if (ref < 0 )
            throw new IllegalStateException("already released");

        return ref;
    }

    public boolean hasRemaining()
    {
        return buffer.hasRemaining();
    }

    public boolean isEmpty()
    {
        return !buffer.hasRemaining();
    }

    @Override
    public String toString()
    {
        return BufferUtil.toDetailString(buffer) + ":r=" + getReferences();
    }
}
