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

package org.eclipse.jetty.util;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A BlockingQueue backed by a circular array capable or growing.
 * <p>
 * This queue is uses a variant of the two lock queue algorithm to provide an efficient queue or list backed by a growable circular array.
 * </p>
 * <p>
 * Unlike {@link java.util.concurrent.ArrayBlockingQueue}, this class is able to grow and provides a blocking put call.
 * </p>
 * <p>
 * The queue has both a capacity (the size of the array currently allocated) and a max capacity (the maximum size that may be allocated), which defaults to
 * {@link Integer#MAX_VALUE}.
 * </p>
 *
 * @param <E> The element type
 */
public class BlockingArrayQueue<E> extends AbstractList<E> implements BlockingQueue<E>
{
    /**
     * The head offset in the {@link #_indexes} array, displaced by 15 slots to avoid false sharing with the array length (stored before the first element of
     * the array itself).
     */
    private static final int HEAD_OFFSET = MemoryUtils.getIntegersPerCacheLine() - 1;
    /**
     * The tail offset in the {@link #_indexes} array, displaced by 16 slots from the head to avoid false sharing with it.
     */
    private static final int TAIL_OFFSET = HEAD_OFFSET + MemoryUtils.getIntegersPerCacheLine();
    /**
     * Default initial capacity, 128.
     */
    public static final int DEFAULT_CAPACITY = 128;
    /**
     * Default growth factor, 64.
     */
    public static final int DEFAULT_GROWTH = 64;

    private final int _maxCapacity;
    private final int _growCapacity;
    /**
     * Array that holds the head and tail indexes, separated by a cache line to avoid false sharing
     */
    private final int[] _indexes = new int[TAIL_OFFSET + 1];
    private final Lock _tailLock = new ReentrantLock();
    private final AtomicInteger _size = new AtomicInteger();
    private final Lock _headLock = new ReentrantLock();
    private final Condition _notEmpty = _headLock.newCondition();
    private Object[] _elements;

    /**
     * Creates an unbounded {@link BlockingArrayQueue} with default initial capacity and grow factor.
     *
     * @see #DEFAULT_CAPACITY
     * @see #DEFAULT_GROWTH
     */
    public BlockingArrayQueue()
    {
        _elements = new Object[DEFAULT_CAPACITY];
        _growCapacity = DEFAULT_GROWTH;
        _maxCapacity = Integer.MAX_VALUE;
    }

    /**
     * Creates a bounded {@link BlockingArrayQueue} that does not grow. The capacity of the queue is fixed and equal to the given parameter.
     *
     * @param maxCapacity the maximum capacity
     */
    public BlockingArrayQueue(int maxCapacity)
    {
        _elements = new Object[maxCapacity];
        _growCapacity = -1;
        _maxCapacity = maxCapacity;
    }

    /**
     * Creates an unbounded {@link BlockingArrayQueue} that grows by the given parameter.
     *
     * @param capacity the initial capacity
     * @param growBy the growth factor
     */
    public BlockingArrayQueue(int capacity, int growBy)
    {
        _elements = new Object[capacity];
        _growCapacity = growBy;
        _maxCapacity = Integer.MAX_VALUE;
    }

    /**
     * Create a bounded {@link BlockingArrayQueue} that grows by the given parameter.
     *
     * @param capacity the initial capacity
     * @param growBy the growth factor
     * @param maxCapacity the maximum capacity
     */
    public BlockingArrayQueue(int capacity, int growBy, int maxCapacity)
    {
        if (capacity > maxCapacity)
            throw new IllegalArgumentException();
        _elements = new Object[capacity];
        _growCapacity = growBy;
        _maxCapacity = maxCapacity;
    }


    /* Collection methods */

    @Override
    public void clear()
    {

        _tailLock.lock();
        try
        {

            _headLock.lock();
            try
            {
                _indexes[HEAD_OFFSET] = 0;
                _indexes[TAIL_OFFSET] = 0;
                _size.set(0);
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    @Override
    public int size()
    {
        return _size.get();
    }

    @Override
    public Iterator<E> iterator()
    {
        return listIterator();
    }


    /* Queue methods */

    @SuppressWarnings("unchecked")
    @Override
    public E poll()
    {
        if (_size.get() == 0)
            return null;

        E e = null;

        _headLock.lock(); // Size cannot shrink
        try
        {
            if (_size.get() > 0)
            {
                final int head = _indexes[HEAD_OFFSET];
                e = (E)_elements[head];
                _elements[head] = null;
                _indexes[HEAD_OFFSET] = (head + 1) % _elements.length;
                if (_size.decrementAndGet() > 0)
                    _notEmpty.signal();
            }
        }
        finally
        {
            _headLock.unlock();
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    @Override
    public E poll(long time, TimeUnit unit) throws InterruptedException
    {
        long nanos = unit.toNanos(time);
        E e = null;

        _headLock.lockInterruptibly(); // Size cannot shrink
        try
        {
            try
            {
                while (_size.get() == 0)
                {
                    if (nanos <= 0)
                        return null;
                    nanos = _notEmpty.awaitNanos(nanos);
                }
            }
            catch (InterruptedException x)
            {
                _notEmpty.signal();
                throw x;
            }

            int head = _indexes[HEAD_OFFSET];
            e = (E)_elements[head];
            _elements[head] = null;
            _indexes[HEAD_OFFSET] = (head + 1) % _elements.length;

            if (_size.decrementAndGet() > 0)
                _notEmpty.signal();
        }
        finally
        {
            _headLock.unlock();
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    @Override
    public E peek()
    {
        if (_size.get() == 0)
            return null;

        E e = null;

        _headLock.lock(); // Size cannot shrink
        try
        {
            if (_size.get() > 0)
                e = (E)_elements[_indexes[HEAD_OFFSET]];
        }
        finally
        {
            _headLock.unlock();
        }
        return e;
    }

    @Override
    public E remove()
    {
        E e = poll();
        if (e == null)
            throw new NoSuchElementException();
        return e;
    }

    @SuppressWarnings("unchecked")
    @Override
    public E remove(int index)
    {

        _tailLock.lock();
        try
        {

            _headLock.lock();
            try
            {
                if (index < 0 || index >= _size.get())
                    throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");

                int i = _indexes[HEAD_OFFSET] + index;
                int capacity = _elements.length;
                if (i >= capacity)
                    i -= capacity;
                E old = (E)_elements[i];

                int tail = _indexes[TAIL_OFFSET];
                if (i < tail)
                {
                    System.arraycopy(_elements, i + 1, _elements, i, tail - i);
                    --_indexes[TAIL_OFFSET];
                }
                else
                {
                    System.arraycopy(_elements, i + 1, _elements, i, capacity - i - 1);
                    _elements[capacity - 1] = _elements[0];
                    if (tail > 0)
                    {
                        System.arraycopy(_elements, 1, _elements, 0, tail);
                        --_indexes[TAIL_OFFSET];
                    }
                    else
                    {
                        _indexes[TAIL_OFFSET] = capacity - 1;
                    }
                    _elements[_indexes[TAIL_OFFSET]] = null;
                }

                _size.decrementAndGet();

                return old;
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    @Override
    public boolean remove(Object o)
    {

        _tailLock.lock();
        try
        {

            _headLock.lock();
            try
            {
                if (isEmpty())
                    return false;

                final int head = _indexes[HEAD_OFFSET];
                final int tail = _indexes[TAIL_OFFSET];
                final int capacity = _elements.length;

                int i = head;
                while (true)
                {
                    if (Objects.equals(_elements[i], o))
                    {
                        remove(i >= head ? i - head : capacity - head + i);
                        return true;
                    }
                    ++i;
                    if (i == capacity)
                        i = 0;
                    if (i == tail)
                        return false;
                }
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    @Override
    public E element()
    {
        E e = peek();
        if (e == null)
            throw new NoSuchElementException();
        return e;
    }


    /* BlockingQueue methods */

    @Override
    public boolean offer(E e)
    {
        Objects.requireNonNull(e);

        boolean notEmpty = false;
        _tailLock.lock(); // Size cannot grow... only shrink
        try
        {
            int size = _size.get();
            if (size >= _maxCapacity)
                return false;

            // Should we expand array?
            if (size == _elements.length)
            {
                _headLock.lock();
                try
                {
                    if (!grow())
                        return false;
                }
                finally
                {
                    _headLock.unlock();
                }
            }

            // Re-read head and tail after a possible grow
            int tail = _indexes[TAIL_OFFSET];
            _elements[tail] = e;
            _indexes[TAIL_OFFSET] = (tail + 1) % _elements.length;
            notEmpty = _size.getAndIncrement() == 0;
        }
        finally
        {
            _tailLock.unlock();
        }

        if (notEmpty)
        {
            _headLock.lock();
            try
            {
                _notEmpty.signal();
            }
            finally
            {
                _headLock.unlock();
            }
        }

        return true;
    }

    @Override
    public boolean offer(E o, long timeout, TimeUnit unit) throws InterruptedException
    {
        // The mechanism to await and signal when the queue is full is not implemented
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean add(E e)
    {
        if (offer(e))
            return true;
        else
            throw new IllegalStateException();
    }

    @Override
    public void add(int index, E e)
    {
        if (e == null)
            throw new NullPointerException();

        _tailLock.lock();
        try
        {

            _headLock.lock();
            try
            {
                final int size = _size.get();

                if (index < 0 || index > size)
                    throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");

                if (index == size)
                {
                    add(e);
                }
                else
                {
                    if (_indexes[TAIL_OFFSET] == _indexes[HEAD_OFFSET])
                        if (!grow())
                            throw new IllegalStateException("full");

                    // Re-read head and tail after a possible grow
                    int i = _indexes[HEAD_OFFSET] + index;
                    int capacity = _elements.length;

                    if (i >= capacity)
                        i -= capacity;

                    _size.incrementAndGet();
                    int tail = _indexes[TAIL_OFFSET];
                    _indexes[TAIL_OFFSET] = tail = (tail + 1) % capacity;

                    if (i < tail)
                    {
                        System.arraycopy(_elements, i, _elements, i + 1, tail - i);
                        _elements[i] = e;
                    }
                    else
                    {
                        if (tail > 0)
                        {
                            System.arraycopy(_elements, 0, _elements, 1, tail);
                            _elements[0] = _elements[capacity - 1];
                        }

                        System.arraycopy(_elements, i, _elements, i + 1, capacity - i - 1);
                        _elements[i] = e;
                    }
                }
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    @Override
    public void put(E o) throws InterruptedException
    {
        // The mechanism to await and signal when the queue is full is not implemented
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public E take() throws InterruptedException
    {
        E e = null;

        _headLock.lockInterruptibly(); // Size cannot shrink
        try
        {
            try
            {
                while (_size.get() == 0)
                {
                    _notEmpty.await();
                }
            }
            catch (InterruptedException ex)
            {
                _notEmpty.signal();
                throw ex;
            }

            final int head = _indexes[HEAD_OFFSET];
            e = (E)_elements[head];
            _elements[head] = null;
            _indexes[HEAD_OFFSET] = (head + 1) % _elements.length;

            if (_size.decrementAndGet() > 0)
                _notEmpty.signal();
        }
        finally
        {
            _headLock.unlock();
        }
        return e;
    }

    @Override
    public int remainingCapacity()
    {

        _tailLock.lock();
        try
        {

            _headLock.lock();
            try
            {
                return getCapacity() - size();
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
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
        int elements = 0;
        _tailLock.lock();
        try
        {
            _headLock.lock();
            try
            {
                if (_size.get() == 0)
                    return 0;

                final int head = _indexes[HEAD_OFFSET];
                final int tail = _indexes[TAIL_OFFSET];
                final int capacity = _elements.length;

                int i = head;
                while (elements < maxElements)
                {
                    if (i == tail && elements > 0)
                        break;

                    elements++;
                    c.add((E)_elements[i]);
                    ++i;
                    if (i == capacity)
                        i = 0;
                }

                if (i == tail)
                {
                    _indexes[HEAD_OFFSET] = 0;
                    _indexes[TAIL_OFFSET] = 0;
                    _size.set(0);
                }
                else
                {
                    _indexes[HEAD_OFFSET] = i;
                    _size.addAndGet(-elements);
                }
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
        return elements;
    }


    /* List methods */

    @SuppressWarnings("unchecked")
    @Override
    public E get(int index)
    {

        _tailLock.lock();
        try
        {
            _headLock.lock();
            try
            {
                if (index < 0 || index >= _size.get())
                    throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");
                int i = _indexes[HEAD_OFFSET] + index;
                int capacity = _elements.length;
                if (i >= capacity)
                    i -= capacity;
                return (E)_elements[i];
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public E set(int index, E e)
    {
        Objects.requireNonNull(e);

        _tailLock.lock();
        try
        {

            _headLock.lock();
            try
            {
                if (index < 0 || index >= _size.get())
                    throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");

                int i = _indexes[HEAD_OFFSET] + index;
                int capacity = _elements.length;
                if (i >= capacity)
                    i -= capacity;
                E old = (E)_elements[i];
                _elements[i] = e;
                return old;
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    @Override
    public ListIterator<E> listIterator(int index)
    {

        _tailLock.lock();
        try
        {

            _headLock.lock();
            try
            {
                Object[] elements = new Object[size()];
                if (size() > 0)
                {
                    int head = _indexes[HEAD_OFFSET];
                    int tail = _indexes[TAIL_OFFSET];
                    if (head < tail)
                    {
                        System.arraycopy(_elements, head, elements, 0, tail - head);
                    }
                    else
                    {
                        int chunk = _elements.length - head;
                        System.arraycopy(_elements, head, elements, 0, chunk);
                        System.arraycopy(_elements, 0, elements, chunk, tail);
                    }
                }
                return new Itr(elements, index);
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    /**
     * @return the current capacity of this queue
     */
    public int getCapacity()
    {
        _tailLock.lock();
        try
        {
            return _elements.length;
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    /**
     * @return the max capacity of this queue, or -1 if this queue is unbounded
     */
    public int getMaxCapacity()
    {
        return _maxCapacity;
    }

    private boolean grow()
    {
        if (_growCapacity <= 0)
            return false;

        _tailLock.lock();
        try
        {

            _headLock.lock();
            try
            {
                final int head = _indexes[HEAD_OFFSET];
                final int tail = _indexes[TAIL_OFFSET];
                final int newTail;
                final int capacity = _elements.length;

                Object[] elements = new Object[capacity + _growCapacity];

                if (head < tail)
                {
                    newTail = tail - head;
                    System.arraycopy(_elements, head, elements, 0, newTail);
                }
                else if (head > tail || _size.get() > 0)
                {
                    newTail = capacity + tail - head;
                    int cut = capacity - head;
                    System.arraycopy(_elements, head, elements, 0, cut);
                    System.arraycopy(_elements, 0, elements, cut, tail);
                }
                else
                {
                    newTail = 0;
                }

                _elements = elements;
                _indexes[HEAD_OFFSET] = 0;
                _indexes[TAIL_OFFSET] = newTail;
                return true;
            }
            finally
            {
                _headLock.unlock();
            }
        }
        finally
        {
            _tailLock.unlock();
        }
    }

    private class Itr implements ListIterator<E>
    {
        private final Object[] _elements;
        private int _cursor;

        public Itr(Object[] elements, int offset)
        {
            _elements = elements;
            _cursor = offset;
        }

        @Override
        public boolean hasNext()
        {
            return _cursor < _elements.length;
        }

        @SuppressWarnings("unchecked")
        @Override
        public E next()
        {
            return (E)_elements[_cursor++];
        }

        @Override
        public boolean hasPrevious()
        {
            return _cursor > 0;
        }

        @SuppressWarnings("unchecked")
        @Override
        public E previous()
        {
            return (E)_elements[--_cursor];
        }

        @Override
        public int nextIndex()
        {
            return _cursor + 1;
        }

        @Override
        public int previousIndex()
        {
            return _cursor - 1;
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(E e)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(E e)
        {
            throw new UnsupportedOperationException();
        }
    }
}
