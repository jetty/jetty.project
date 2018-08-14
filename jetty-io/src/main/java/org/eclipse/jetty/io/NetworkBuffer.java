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

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Releasable;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>NetworkBuffer wraps a {@link ByteBuffer} and tracks via reference counting
 * usages of the wrapped {@code ByteBuffer}.</p>
 * <p>When handing the wrapped {@code ByteBuffer} to application code that may retain it,
 * the implementation should call {@link #retain()}, which will increase the reference
 * count, then pass to the application code the wrapped {@code ByteBuffer} - naked -
 * along with the NetworkBuffer itself as a {@link Callback}.</p>
 * <p>The application can signal that it has finished using the wrapped {@code ByteBuffer}
 * by calling {@link #succeeded()}, which in turn calls {@link #unretain()}, which will
 * decrement the reference count.</p>
 * <p>The return value of the {@link #unretain()} method determines whether {@link #recycle()}
 * should be called to return the wrapped {@code ByteBuffer} to the {@link ByteBufferPool}.</p>
 */
public class NetworkBuffer implements Retainable, Releasable, Callback
{
    private static final Logger LOG = Log.getLogger(NetworkBuffer.class);

    private final AtomicInteger refCount = new AtomicInteger();
    private final ByteBufferPool byteBufferPool;
    private final ByteBuffer buffer;

    public NetworkBuffer(ByteBufferPool byteBufferPool, int bufferSize, boolean direct)
    {
        this.byteBufferPool = byteBufferPool;
        this.buffer = byteBufferPool.acquire(bufferSize, direct);
    }

    public ByteBuffer getByteBuffer()
    {
        return buffer;
    }

    public void put(ByteBuffer source)
    {
        BufferUtil.append(buffer, source);
    }

    public boolean hasRemaining()
    {
        return buffer.hasRemaining();
    }

    public void clear()
    {
        BufferUtil.clear(buffer);
    }

    @Override
    public void retain()
    {
        refCount.incrementAndGet();
    }

    @Override
    public void release()
    {
        if (unretain())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Released retained {}", this);
            recycle();
        }
    }

    public boolean unretain()
    {
        return refCount.decrementAndGet() == 0;
    }

    @Override
    public void succeeded()
    {
        release();
    }

    @Override
    public void failed(Throwable failure)
    {
        if (unretain())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Released retained " + this, failure);
            recycle();
        }
    }

    @Override
    public InvocationType getInvocationType()
    {
        return InvocationType.NON_BLOCKING;
    }

    public void recycle()
    {
        byteBufferPool.release(buffer);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[refs=%s,%s]", getClass().getSimpleName(), hashCode(), refCount, buffer);
    }
}
