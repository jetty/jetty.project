//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

@ManagedObject
abstract class AbstractByteBufferPool implements ByteBufferPool
{
    private final int _factor;
    private final int _maxQueueLength;
    private final long _maxHeapMemory;
    private final AtomicLong _heapMemory = new AtomicLong();
    private final long _maxDirectMemory;
    private final AtomicLong _directMemory = new AtomicLong();

    protected AbstractByteBufferPool(int factor, int maxQueueLength, long maxHeapMemory, long maxDirectMemory)
    {
        _factor = factor <= 0 ? 1024 : factor;
        _maxQueueLength = maxQueueLength;
        _maxHeapMemory = maxHeapMemory;
        _maxDirectMemory = maxDirectMemory;
    }

    protected int getCapacityFactor()
    {
        return _factor;
    }

    protected int getMaxQueueLength()
    {
        return _maxQueueLength;
    }

    protected void decrementMemory(ByteBuffer buffer)
    {
        updateMemory(buffer, false);
    }

    protected void incrementMemory(ByteBuffer buffer)
    {
        updateMemory(buffer, true);
    }

    private void updateMemory(ByteBuffer buffer, boolean addOrSub)
    {
        AtomicLong memory = buffer.isDirect() ? _directMemory : _heapMemory;
        int capacity = buffer.capacity();
        memory.addAndGet(addOrSub ? capacity : -capacity);
    }

    protected void releaseExcessMemory(boolean direct, Consumer<Boolean> clearFn)
    {
        long maxMemory = direct ? _maxDirectMemory : _maxHeapMemory;
        if (maxMemory > 0)
        {
            while (getMemory(direct) > maxMemory)
            {
                clearFn.accept(direct);
            }
        }
    }

    @ManagedAttribute("The bytes retained by direct ByteBuffers")
    public long getDirectMemory()
    {
        return getMemory(true);
    }

    @ManagedAttribute("The bytes retained by heap ByteBuffers")
    public long getHeapMemory()
    {
        return getMemory(false);
    }

    public long getMemory(boolean direct)
    {
        AtomicLong memory = direct ? _directMemory : _heapMemory;
        return memory.get();
    }

    @ManagedOperation(value = "Clears this ByteBufferPool", impact = "ACTION")
    public void clear()
    {
        _heapMemory.set(0);
        _directMemory.set(0);
    }
}
