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
 * <p/>
 * This queue is uses  a variant of the two lock queue algorithm to provide an
 * efficient queue or list backed by a growable circular array.
 * <p/>
 * Unlike {@link java.util.concurrent.ArrayBlockingQueue}, this class is
 * able to grow and provides a blocking put call.
 * <p/>
 * The queue has both a capacity (the size of the array currently allocated)
 * and a max capacity (the maximum size that may be allocated), which defaults to
 * {@link Integer#MAX_VALUE}.
 *
 * @param <E> The element type
 */
public class BlockingArrayQueue<E> extends AbstractList<E> implements BlockingQueue<E>
{
    /**
     * Default initial capacity, 128.
     */
    public final int DEFAULT_CAPACITY = 128;
    /**
     * Default growth factor, 64.
     */
    public final int DEFAULT_GROWTH = 64;

    private final int _maxCapacity;
    private final AtomicInteger _size = new AtomicInteger();
    private final int _growCapacity;
    private Object[] _elements;
    private final Lock _headLock = new ReentrantLock();
    private final Condition _notEmpty = _headLock.newCondition();
    private int _head;
    // Spacers created to prevent false sharing between head and tail http://en.wikipedia.org/wiki/False_sharing
    // TODO verify these spacers really prevent false sharing
    private long _space0;
    private long _space1;
    private long _space2;
    private long _space3;
    private long _space4;
    private long _space5;
    private long _space6;
    private long _space7;
    private final Lock _tailLock = new ReentrantLock();
    private int _tail;

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
     * Creates a bounded {@link BlockingArrayQueue} that does not grow.
     * The capacity of the queue is fixed and equal to the given parameter.
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
     * @param growBy   the growth factor
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
     * @param growBy   the growth factor
     * @param maxCapacity    the maximum capacity
     */
    public BlockingArrayQueue(int capacity, int growBy, int maxCapacity)
    {
        if (capacity > maxCapacity)
            throw new IllegalArgumentException();
        _elements = new Object[capacity];
        _growCapacity = growBy;
        _maxCapacity = maxCapacity;
    }

    /*----------------------------------------------------------------------------*/
    /* Collection methods                                                         */
    /*----------------------------------------------------------------------------*/

    @Override
    public void clear()
    {
        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
            try
            {
                _head = 0;
                _tail = 0;
                _size.set(0);
            }
            finally
            {
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
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

    /*----------------------------------------------------------------------------*/
    /* Queue methods                                                              */
    /*----------------------------------------------------------------------------*/

    @SuppressWarnings("unchecked")
    @Override
    public E poll()
    {
        if (_size.get() == 0)
            return null;

        E e = null;
        final Lock headLock = _headLock;
        headLock.lock(); // Size cannot shrink
        try
        {
            if (_size.get() > 0)
            {
                final int head = _head;
                e = (E)_elements[head];
                _elements[head] = null;
                _head = (head + 1) % _elements.length;
                if (_size.decrementAndGet() > 0)
                    _notEmpty.signal();
            }
        }
        finally
        {
            headLock.unlock();
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
        final Lock headLock = _headLock;
        headLock.lock(); // Size cannot shrink
        try
        {
            if (_size.get() > 0)
                e = (E)_elements[_head];
        }
        finally
        {
            headLock.unlock();
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

    @Override
    public E element()
    {
        E e = peek();
        if (e == null)
            throw new NoSuchElementException();
        return e;
    }

    /*----------------------------------------------------------------------------*/
    /* BlockingQueue methods                                                      */
    /*----------------------------------------------------------------------------*/

    @Override
    public boolean offer(E e)
    {
        Objects.requireNonNull(e);

        final Lock tailLock = _tailLock;
        final Lock headLock = _headLock;
        boolean notEmpty = false;
        tailLock.lock(); // Size cannot grow... only shrink
        try
        {
            int size = _size.get();
            if (size >= _maxCapacity)
                return false;

            // Should we expand array?
            if (size == _elements.length)
            {
                headLock.lock();
                try
                {
                    if (!grow())
                        return false;
                }
                finally
                {
                    headLock.unlock();
                }
            }

            // Must re-read fields since there may have been a grow
            // Add the element
            int tail = _tail;
            _elements[tail] = e;
            _tail = (tail + 1) % _elements.length;
            notEmpty = _size.getAndIncrement() == 0;
        }
        finally
        {
            tailLock.unlock();
        }

        if (notEmpty)
        {
            headLock.lock();
            try
            {
                _notEmpty.signal();
            }
            finally
            {
                headLock.unlock();
            }
        }

        return true;
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
    public void put(E o) throws InterruptedException
    {
        // The mechanism to await and signal when the queue is full is not implemented
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean offer(E o, long timeout, TimeUnit unit) throws InterruptedException
    {
        // The mechanism to await and signal when the queue is full is not implemented
        throw new UnsupportedOperationException();
    }

    @SuppressWarnings("unchecked")
    @Override
    public E take() throws InterruptedException
    {
        E e = null;
        final Lock headLock = _headLock;
        headLock.lockInterruptibly(); // Size cannot shrink
        try
        {
            try
            {
                while (_size.get() == 0)
                {
                    _notEmpty.await();
                }
            }
            catch (InterruptedException ie)
            {
                _notEmpty.signal();
                throw ie;
            }

            final int head = _head;
            e = (E)_elements[head];
            _elements[head] = null;
            _head = (head + 1) % _elements.length;

            if (_size.decrementAndGet() > 0)
                _notEmpty.signal();
        }
        finally
        {
            headLock.unlock();
        }
        return e;
    }

    @SuppressWarnings("unchecked")
    @Override
    public E poll(long time, TimeUnit unit) throws InterruptedException
    {
        long nanos = unit.toNanos(time);
        E e = null;
        final Lock headLock = _headLock;
        headLock.lockInterruptibly(); // Size cannot shrink
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

            int head = _head;
            e = (E)_elements[head];
            _elements[head] = null;
            _head = (head + 1) % _elements.length;

            if (_size.decrementAndGet() > 0)
                _notEmpty.signal();
        }
        finally
        {
            headLock.unlock();
        }
        return e;
    }

    @Override
    public boolean remove(Object o)
    {
        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
            try
            {
                if (isEmpty())
                    return false;

                final int head = _head;
                final int tail = _tail;
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
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
        }
    }

    @Override
    public int remainingCapacity()
    {
        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
            try
            {
                return getCapacity() - size();
            }
            finally
            {
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
        }
    }

    @Override
    public int drainTo(Collection<? super E> c)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public int drainTo(Collection<? super E> c, int maxElements)
    {
        throw new UnsupportedOperationException();
    }

    /*----------------------------------------------------------------------------*/
    /* List methods                                                               */
    /*----------------------------------------------------------------------------*/

    @SuppressWarnings("unchecked")
    @Override
    public E get(int index)
    {
        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
            try
            {
                if (index < 0 || index >= _size.get())
                    throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");
                int i = _head + index;
                int capacity = _elements.length;
                if (i >= capacity)
                    i -= capacity;
                return (E)_elements[i];
            }
            finally
            {
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
        }
    }

    @Override
    public void add(int index, E e)
    {
        if (e == null)
            throw new NullPointerException();

        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
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
                    if (_tail == _head)
                        if (!grow())
                            throw new IllegalStateException("full");

                    int i = _head + index;
                    int capacity = _elements.length;

                    if (i >= capacity)
                        i -= capacity;

                    _size.incrementAndGet();
                    int tail = _tail;
                    _tail = tail = (tail + 1) % capacity;

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
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public E set(int index, E e)
    {
        Objects.requireNonNull(e);

        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
            try
            {
                if (index < 0 || index >= _size.get())
                    throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");

                int i = _head + index;
                int capacity = _elements.length;
                if (i >= capacity)
                    i -= capacity;
                E old = (E)_elements[i];
                _elements[i] = e;
                return old;
            }
            finally
            {
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public E remove(int index)
    {
        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
            try
            {
                if (index < 0 || index >= _size.get())
                    throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");

                int i = _head + index;
                int capacity = _elements.length;
                if (i >= capacity)
                    i -= capacity;
                E old = (E)_elements[i];

                int tail = _tail;
                if (i < tail)
                {
                    System.arraycopy(_elements, i + 1, _elements, i, tail - i);
                    --_tail;
                }
                else
                {
                    System.arraycopy(_elements, i + 1, _elements, i, capacity - i - 1);
                    _elements[capacity - 1] = _elements[0];
                    if (tail > 0)
                    {
                        System.arraycopy(_elements, 1, _elements, 0, tail);
                        --_tail;
                    }
                    else
                    {
                        _tail = capacity - 1;
                    }
                    _elements[_tail] = null;
                }

                _size.decrementAndGet();

                return old;
            }
            finally
            {
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
        }
    }

    @Override
    public ListIterator<E> listIterator(int index)
    {
        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
            try
            {
                Object[] elements = new Object[size()];
                if (size() > 0)
                {
                    if (_head < _tail)
                    {
                        System.arraycopy(_elements, _head, elements, 0, _tail - _head);
                    }
                    else
                    {
                        int chunk = _elements.length - _head;
                        System.arraycopy(_elements, _head, elements, 0, chunk);
                        System.arraycopy(_elements, 0, elements, chunk, _tail);
                    }
                }
                return new Itr(elements, index);
            }
            finally
            {
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
        }
    }

    /*----------------------------------------------------------------------------*/
    /* Additional methods                                                         */
    /*----------------------------------------------------------------------------*/

    /**
     * @return the current capacity of this queue
     */
    public int getCapacity()
    {
        return _elements.length;
    }

    /**
     * @return the max capacity of this queue, or -1 if this queue is unbounded
     */
    public int getMaxCapacity()
    {
        return _maxCapacity;
    }

    /*----------------------------------------------------------------------------*/
    /* Implementation methods                                                     */
    /*----------------------------------------------------------------------------*/

    private boolean grow()
    {
        if (_growCapacity <= 0)
            return false;

        final Lock tailLock = _tailLock;
        tailLock.lock();
        try
        {
            final Lock headLock = _headLock;
            headLock.lock();
            try
            {
                final int head = _head;
                final int tail = _tail;
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
                _head = 0;
                _tail = newTail;
                return true;
            }
            finally
            {
                headLock.unlock();
            }
        }
        finally
        {
            tailLock.unlock();
        }
    }

    // TODO: verify this is not optimized away by the JIT
    long sumOfSpace()
    {
        // this method exists to stop clever optimisers removing the spacers
        return _space0++ + _space1++ + _space2++ + _space3++ + _space4++ + _space5++ + _space6++ + _space7++;
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
