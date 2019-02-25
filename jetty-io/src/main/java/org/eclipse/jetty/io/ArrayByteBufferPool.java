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

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;

/**
 * <p>A ByteBuffer pool where ByteBuffers are held in queues that are held in array elements.</p>
 * <p>Given a capacity {@code factor} of 1024, the first array element holds a queue of ByteBuffers
 * each of capacity 1024, the second array element holds a queue of ByteBuffers each of capacity
 * 2048, and so on.</p>
 */
@ManagedObject
public class ArrayByteBufferPool implements ByteBufferPool
{
    private final int _minCapacity;
    private final ByteBufferPool.Bucket[] _direct;
    private final ByteBufferPool.Bucket[] _indirect;
    private final int _factor;

    /**
     * Creates a new ArrayByteBufferPool with a default configuration.
     */
    public ArrayByteBufferPool()
    {
        this(-1, -1, -1, -1);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity)
    {
        this(minCapacity, factor, maxCapacity, -1);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxQueueLength the maximum ByteBuffer queue length
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxQueueLength)
    {
        if (minCapacity <= 0)
            minCapacity = 0;
        if (factor <= 0)
            factor = 1024;
        if (maxCapacity <= 0)
            maxCapacity = 64 * 1024;
        if ((maxCapacity % factor) != 0 || factor >= maxCapacity)
            throw new IllegalArgumentException("The capacity factor must be a divisor of maxCapacity");
        _minCapacity = minCapacity;
        _factor = factor;

        int length = maxCapacity / factor;
        _direct = new ByteBufferPool.Bucket[length];
        _indirect = new ByteBufferPool.Bucket[length];

        int capacity = 0;
        for (int i = 0; i < _direct.length; ++i)
        {
            capacity += _factor;
            _direct[i] = new ByteBufferPool.Bucket(this, capacity, maxQueueLength);
            _indirect[i] = new ByteBufferPool.Bucket(this, capacity, maxQueueLength);
        }
    }

    @Override
    public ByteBuffer acquire(int size, boolean direct)
    {
        ByteBufferPool.Bucket bucket = bucketFor(size, direct);
        if (bucket == null)
            return newByteBuffer(size, direct);
        return bucket.acquire(direct);
    }

    @Override
    public void release(ByteBuffer buffer)
    {
        if (buffer != null)
        {
            ByteBufferPool.Bucket bucket = bucketFor(buffer.capacity(), buffer.isDirect());
            if (bucket != null)
                bucket.release(buffer);
        }
    }

    @ManagedOperation(value = "Clears this ByteBufferPool", impact = "ACTION")
    public void clear()
    {
        for (int i = 0; i < _direct.length; ++i)
        {
            _direct[i].clear();
            _indirect[i].clear();
        }
    }

    private ByteBufferPool.Bucket bucketFor(int capacity, boolean direct)
    {
        if (capacity <= _minCapacity)
            return null;
        int b = (capacity - 1) / _factor;
        if (b >= _direct.length)
            return null;
        return bucketsFor(direct)[b];
    }

    // Package local for testing
    ByteBufferPool.Bucket[] bucketsFor(boolean direct)
    {
        return direct ? _direct : _indirect;
    }
}
