//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
        AtomicLong memory = buffer.isDirect() ? _directMemory : _heapMemory;
        memory.addAndGet(-buffer.capacity());
    }

    protected boolean incrementMemory(ByteBuffer buffer)
    {
        boolean direct = buffer.isDirect();
        int capacity = buffer.capacity();
        long maxMemory = direct ? _maxDirectMemory : _maxHeapMemory;
        AtomicLong memory = direct ? _directMemory : _heapMemory;
        while (true)
        {
            long current = memory.get();
            long value = current + capacity;
            if (maxMemory > 0 && value > maxMemory)
                return false;
            if (memory.compareAndSet(current, value))
                return true;
        }
    }

    @ManagedOperation(value = "Returns the memory occupied by this ByteBufferPool in bytes", impact = "INFO")
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
