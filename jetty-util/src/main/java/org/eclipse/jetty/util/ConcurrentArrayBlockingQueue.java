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
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Common functionality for a blocking version of {@link ConcurrentArrayQueue}.
 *
 * @see Unbounded
 * @see Bounded
 * @param <E>
 */
public abstract class ConcurrentArrayBlockingQueue<E> extends ConcurrentArrayQueue<E> implements BlockingQueue<E>
{
    private final Lock _lock = new ReentrantLock();
    private final Condition _consumer = _lock.newCondition();

    public ConcurrentArrayBlockingQueue(int blockSize)
    {
        super(blockSize);
    }

    @Override
    public E poll()
    {
        E result = super.poll();
        if (result != null && decrementAndGetSize() > 0)
            signalConsumer();
        return result;
    }

    @Override
    public boolean remove(Object o)
    {
        boolean result = super.remove(o);
        if (result && decrementAndGetSize() > 0)
            signalConsumer();
        return result;
    }

    protected abstract int decrementAndGetSize();

    protected void signalConsumer()
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
    public E take() throws InterruptedException
    {
        while (true)
        {
            E result = poll();
            if (result != null)
                return result;

            final Lock lock = _lock;
            lock.lockInterruptibly();
            try
            {
                if (size() == 0)
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
    public E poll(long timeout, TimeUnit unit) throws InterruptedException
    {
        long nanos = unit.toNanos(timeout);
        
        while (true)
        {
            // TODO should reduce nanos if we spin here
            
            E result = poll();
            if (result != null)
                return result;

            final Lock lock = _lock;
            lock.lockInterruptibly();
            try
            {
                if (size() == 0)
                {
                    if (nanos <= 0)
                        return null;
                    nanos = _consumer.awaitNanos(nanos);
                }
            }
            finally
            {
                lock.unlock();
            }
        }
    }

    @Override
    public int drainTo(Collection<? super E> c)
    {
        return drainTo(c, Integer.MAX_VALUE);
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements)
    {
        if (c == this)
            throw new IllegalArgumentException();

        int added = 0;
        while (added < maxElements)
        {
            E element = poll();
            if (element == null)
                break;
            c.add(element);
            ++added;
        }
        return added;
    }

    /**
     * An unbounded, blocking version of {@link ConcurrentArrayQueue}.
     *
     * @param <E>
     */
    public static class Unbounded<E> extends ConcurrentArrayBlockingQueue<E>
    {
        private static final int SIZE_LEFT_OFFSET = MemoryUtils.getLongsPerCacheLine() - 1;
        private static final int SIZE_RIGHT_OFFSET = SIZE_LEFT_OFFSET + MemoryUtils.getLongsPerCacheLine();
        
        private final AtomicLongArray _sizes = new AtomicLongArray(SIZE_RIGHT_OFFSET+1);

        public Unbounded()
        {
            this(DEFAULT_BLOCK_SIZE);
        }

        public Unbounded(int blockSize)
        {
            super(blockSize);
        }

        @Override
        public boolean offer(E item)
        {
            boolean result = super.offer(item);
            if (result && getAndIncrementSize() == 0)
                signalConsumer();
            return result;
        }

        private int getAndIncrementSize()
        {
            long sizeRight = _sizes.getAndIncrement(SIZE_RIGHT_OFFSET);
            long sizeLeft = _sizes.get(SIZE_LEFT_OFFSET);
            return (int)(sizeRight - sizeLeft);
        }

        @Override
        protected int decrementAndGetSize()
        {
            long sizeLeft = _sizes.incrementAndGet(SIZE_LEFT_OFFSET);
            long sizeRight = _sizes.get(SIZE_RIGHT_OFFSET);
            return (int)(sizeRight - sizeLeft);
        }

        @Override
        public int size()
        {
            long sizeLeft = _sizes.get(SIZE_LEFT_OFFSET);
            long sizeRight = _sizes.get(SIZE_RIGHT_OFFSET);
            return (int)(sizeRight - sizeLeft);
        }

        @Override
        public int remainingCapacity()
        {
            return Integer.MAX_VALUE;
        }

        @Override
        public void put(E element) throws InterruptedException
        {
            offer(element);
        }

        @Override
        public boolean offer(E element, long timeout, TimeUnit unit) throws InterruptedException
        {
            return offer(element);
        }
    }

    /**
     * A bounded, blocking version of {@link ConcurrentArrayQueue}.
     *
     * @param <E>
     */
    public static class Bounded<E> extends ConcurrentArrayBlockingQueue<E>
    {
        private final AtomicInteger _size = new AtomicInteger();
        private final Lock _lock = new ReentrantLock();
        private final Condition _producer = _lock.newCondition();
        private final int _capacity;

        public Bounded(int capacity)
        {
            this(DEFAULT_BLOCK_SIZE, capacity);
        }

        public Bounded(int blockSize, int capacity)
        {
            super(blockSize);
            this._capacity = capacity;
        }

        @Override
        public boolean offer(E item)
        {
            while (true)
            {
                int size = size();
                int nextSize = size + 1;

                if (nextSize > _capacity)
                    return false;

                if (_size.compareAndSet(size, nextSize))
                {
                    if (super.offer(item))
                    {
                        if (size == 0)
                            signalConsumer();
                        return true;
                    }
                    else
                    {
                        decrementAndGetSize();
                    }
                }
            }
        }

        @Override
        public E poll()
        {
            E result = super.poll();
            if (result != null)
                signalProducer();
            return result;
        }

        @Override
        public boolean remove(Object o)
        {
            boolean result = super.remove(o);
            if (result)
                signalProducer();
            return result;
        }

        @Override
        protected int decrementAndGetSize()
        {
            return _size.decrementAndGet();
        }

        @Override
        public int size()
        {
            return _size.get();
        }

        @Override
        public int remainingCapacity()
        {
            return _capacity - size();
        }

        @Override
        public void put(E item) throws InterruptedException
        {
            item = Objects.requireNonNull(item);

            while (true)
            {
                final Lock lock = _lock;
                lock.lockInterruptibly();
                try
                {
                    if (size() == _capacity)
                        _producer.await();
                }
                finally
                {
                    lock.unlock();
                }
                if (offer(item))
                    break;
            }
        }

        @Override
        public boolean offer(E item, long timeout, TimeUnit unit) throws InterruptedException
        {
            item = Objects.requireNonNull(item);

            long nanos = unit.toNanos(timeout);
            while (true)
            {
                final Lock lock = _lock;
                lock.lockInterruptibly();
                try
                {
                    if (size() == _capacity)
                    {
                        if (nanos <= 0)
                            return false;
                        nanos = _producer.awaitNanos(nanos);
                    }
                }
                finally
                {
                    lock.unlock();
                }
                if (offer(item))
                    break;
            }

            return true;
        }

        @Override
        public int drainTo(Collection<? super E> c, int maxElements)
        {
            int result = super.drainTo(c, maxElements);
            if (result > 0)
                signalProducers();
            return result;
        }

        @Override
        public void clear()
        {
            super.clear();
            signalProducers();
        }

        private void signalProducer()
        {
            final Lock lock = _lock;
            lock.lock();
            try
            {
                _producer.signal();
            }
            finally
            {
                lock.unlock();
            }
        }

        private void signalProducers()
        {
            final Lock lock = _lock;
            lock.lock();
            try
            {
                _producer.signalAll();
            }
            finally
            {
                lock.unlock();
            }
        }
    }
}
