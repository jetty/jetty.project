//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util;

import java.util.Collection;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class ConcurrentArrayBlockingQueue<T> extends ConcurrentArrayQueue<T> implements BlockingQueue<T>
{
    private static final int HEAD_OFFSET = MemoryUtils.getLongsPerCacheLine() - 1;
    private static final int TAIL_OFFSET = HEAD_OFFSET + MemoryUtils.getLongsPerCacheLine();
    private static final long SIZE_LEFT_OFFSET = MemoryUtils.arrayElementOffset(long[].class, HEAD_OFFSET);
    private static final long SIZE_RIGHT_OFFSET = MemoryUtils.arrayElementOffset(long[].class, TAIL_OFFSET);

    private final long[] _sizes = new long[TAIL_OFFSET + 1];
    private final Lock _lock = new ReentrantLock();
    private final Condition _consumer = _lock.newCondition();

    public ConcurrentArrayBlockingQueue()
    {
    }

    public ConcurrentArrayBlockingQueue(int blockSize)
    {
        super(blockSize);
    }

    @Override
    public boolean offer(T item)
    {
        boolean result = super.offer(item);
        if (result)
            incrementSize();
        return result;
    }

    private void incrementSize()
    {
        long sizeLeft = MemoryUtils.getLongVolatile(_sizes, SIZE_LEFT_OFFSET);
        long sizeRight = MemoryUtils.getLongAndIncrement(_sizes, SIZE_RIGHT_OFFSET);
        if (sizeRight == sizeLeft)
            signalNotEmpty();
    }

    @Override
    public T poll()
    {
        T result = super.poll();
        if (result != null)
            decrementSize();
        return result;
    }

    @Override
    public boolean remove(Object o)
    {
        boolean result = super.remove(o);
        if (result)
            decrementSize();
        return result;
    }

    private void decrementSize()
    {
        long sizeLeft = MemoryUtils.incrementAndGetLong(_sizes, SIZE_LEFT_OFFSET);
        long sizeRight = MemoryUtils.getLongVolatile(_sizes, SIZE_RIGHT_OFFSET);
        if (sizeRight != sizeLeft)
            signalNotEmpty();
    }

    private void signalNotEmpty()
    {
        final Lock lock = _lock;
        lock.lock();
        try
        {
            _consumer.signal();
        }
        finally
        {
            lock.unlock();
        }
    }

    @Override
    public int size()
    {
        long sizeLeft = MemoryUtils.getLongVolatile(_sizes, SIZE_LEFT_OFFSET);
        long sizeRight = MemoryUtils.getLongVolatile(_sizes, SIZE_RIGHT_OFFSET);
        return (int)(sizeRight - sizeLeft);
    }

    @Override
    public T take() throws InterruptedException
    {
        while (true)
        {
            T result = poll();
            if (result != null)
                return result;

            final Lock lock = _lock;
            lock.lockInterruptibly();
            try
            {
                while (size() == 0)
                {
                    _consumer.await();
                }
            }
            finally
            {
                lock.unlock();
            }
        }
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException
    {
        long nanos = unit.toNanos(timeout);
        while (true)
        {
            T result = poll();
            if (result != null)
                return result;

            final Lock lock = _lock;
            lock.lockInterruptibly();
            try
            {
                if (size() == 0)
                {
                    nanos = _consumer.awaitNanos(nanos);
                    if (nanos <= 0)
                        return null;
                }
            }
            finally
            {
                lock.unlock();
            }
        }
    }

    @Override
    public void put(T t) throws InterruptedException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int remainingCapacity()
    {
        return Integer.MAX_VALUE;
    }

    @Override
    public int drainTo(Collection<? super T> c)
    {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super T> c, int maxElements)
    {
        if (c == this)
            throw new IllegalArgumentException();

        int added = 0;
        while (added < maxElements)
        {
            T element = poll();
            if (element == null)
                break;
            c.add(element);
            ++added;
        }
        return added;
    }
}
