//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.thread.AutoLock;

/**
 * A BlockingQueue backed by a circular array capable of growing.
 * <p>
 * This queue is uses a variant of the two lock queue algorithm to provide an efficient queue or list backed by a growable circular array.
 * </p>
 * <p>
 * Unlike {@link java.util.concurrent.ArrayBlockingQueue}, this class is able to grow and provides a blocking {@link #put(Object)} call.
 * </p>
 * <p>
 * The queue has both a capacity (the size of the array currently allocated) and a max capacity (the maximum size that may be allocated),
 * which defaults to {@link Integer#MAX_VALUE}.
 * </p>
 *
 * @param <E> The element type
 */
public class BlockingArrayQueue<E> extends AbstractList<E> implements BlockingQueue<E>
{
    /*
     * # Using signalAll() vs signal()
     *
     * This class uses two Conditions (`_notFull` on `_tailLock` and `_notEmpty` on `_headLock`) to implement blocking operations.
     * Several strategies for using these {@code Conditions} are possible:
     *
     * ### Always signal one:
     * signal a single waiter on every operation that satisfies the condition
     * (e.g., every put() signals `_notEmpty`, every poll() signals `_notFull`).
     * This tends to wake exactly one waiter per event, but forces each operation to acquire both locks,
     * as in order for a put() to wakeup a poll() it must acquire the head lock to signal `_notEmpty`,
     * and similarly for a poll() to wakeup a put(). This can lead to
     * an increase in producer/consumer contention and largely defeating the two-lock design.
     *
     * ### Signal-all on edge transitions:
     * signal all waiters only when the condition flips:
     *  + signal `_notEmpty` only on a transition from empty to non-empty (0 → 1),
     *  + signal `_notFull` only on a transition from full to not-full (max → max-1).
     * This keeps most operations under a single lock (producer vs consumer), greatly reducing contention.
     * However, signalAll may wake multiple waiters for a single event, with all but one being forced to loop and wait again.
     *
     * ### Signal one on edge transitions and signal one on condition continuity:
     * signal only one waiter when the condition flips:
     *  + signal one `_notEmpty` only on a transition from empty to non-empty ({@code 0 → 1}),
     *  + signal `_notFull` only on a transition from full to not-full ({@code max → max-1}).
     * The woken operation then is recruited to cascade the signal to another waiter, if the operation maintains the condition:
     *  + a poll that leaves the queue non-empty ({@code > 1}) signals `_notEmpty`,
     *  + a put that leaves the queue non-full ({@code < max-1}) signals `_notFull`.
     * This keeps most operations under a single lock as the woken operation already holds the lock required to cascade the
     * signal. This greatly reducing contention without waking multiple waiters per transition.
     * Both locks are only acquired when the condition flips, and only one waiter is woken per operation.
     *
     * This implementation uses the latter strategy, as it provides better throughput, especially for {@code QueuedThreadPool} usage.
     *
     * Bulk operations (e.g., {@link #drainTo(Collection)}) can signal all, as there no woken operations to recruit.
     * It is the "best of both worlds", strategy.
     */

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
     * Old default growth factor, 64.
     * @deprecated the growth strategy has changed and doesn't use this constant anymore.
     */
    @Deprecated(since = "12.1.2", forRemoval = true)
    public static final int DEFAULT_GROWTH = 64;

    /**
     * Array that holds the head and tail indexes, separated by a cache line to avoid false sharing
     */
    private final int[] _indexes = new int[TAIL_OFFSET + 1];
    private final AtomicInteger _size = new AtomicInteger();
    /**
     * Lock for head operations.
     * The head lock is only acquired when taking entries from the queue, thus when held, the size can shrink but not grow.
     * Some operations (e.g. {@link #clear()}) require both the {@link #_tailLock tail lock} and the head lock to be held,
     * in which case the tail lock must be acquired first.
     */
    private final AutoLock.WithCondition _headLock = new AutoLock.WithCondition();
    /**
     * Lock for tail operations.
     * The tail lock is only acquired when adding entries to the queue, thus when held, the size can grow but not shrink.
     * Some operations (e.g. {@link #clear()}) require both this tail lock and the {@link #_headLock head lock} to be held,
     * in which case this lock must be acquired first.
     */
    private final AutoLock.WithCondition _tailLock = new AutoLock.WithCondition();
    /**
     * The array that holds the elements of the queue.
     */
    private Object[] _elements;
    private final int _maxCapacity;

    /**
     * Creates an unbounded instance with default initial capacity.
     *
     * @see #DEFAULT_CAPACITY
     */
    public BlockingArrayQueue()
    {
        this(DEFAULT_CAPACITY, Integer.MAX_VALUE, false);
    }

    /**
     * Creates an instance that does not grow.
     * The capacity of the queue is fixed and equal to the given parameter.
     *
     * @param maxCapacity the maximum capacity
     */
    public BlockingArrayQueue(int maxCapacity)
    {
        this(maxCapacity, maxCapacity, false);
    }

    /**
     * Creates an unbounded instance.
     *
     * @param capacity the initial capacity
     * @param growBy the growth factor
     * @deprecated the growth factor isn't used anymore
     */
    @Deprecated(since = "12.1.2", forRemoval = true)
    public BlockingArrayQueue(int capacity, int growBy)
    {
        this(capacity, Integer.MAX_VALUE, false);
    }

    /**
     * Creates an instance with given initial and max capacities.
     *
     * @param capacity the initial capacity
     * @param growBy the growth factor
     * @param maxCapacity the maximum capacity
     * @deprecated the growth factor isn't used anymore
     */
    @Deprecated(since = "12.1.2", forRemoval = true)
    public BlockingArrayQueue(int capacity, int growBy, int maxCapacity)
    {
        this(capacity, maxCapacity, false);
    }

    /**
     * Creates an instance with given initial and max capacities.
     *
     * @param capacity the initial capacity
     * @param maxCapacity the maximum capacity
     * @param ignored this parameter is ignored but is needed as there is already a
     *  {@link BlockingArrayQueue#BlockingArrayQueue(int, int) deprecated constructor} that takes two ints.
     */
    private BlockingArrayQueue(int capacity, int maxCapacity, boolean ignored)
    {
        if (capacity < 0 || maxCapacity < 0 || capacity > maxCapacity)
            throw new IllegalArgumentException();
        _elements = new Object[capacity];
        _maxCapacity = maxCapacity;
    }

    /**
     * Creates a {@link BlockingArrayQueue} with given initial and max capacities.
     *
     * @param capacity the initial capacity
     * @param maxCapacity the maximum capacity
     */
    public static <E> BlockingArrayQueue<E> newInstance(int capacity, int maxCapacity)
    {
        return new BlockingArrayQueue<>(capacity, maxCapacity, false);
    }

    /* Collection methods */

    @Override
    public void clear()
    {
        // Full lock, _size cannot change.
        try (var tailLock = _tailLock.lock(); var ignored = _headLock.lock())
        {
            _indexes[HEAD_OFFSET] = 0;
            _indexes[TAIL_OFFSET] = 0;
            _size.set(0);
            tailLock.signalAll();
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

    @Override
    public E poll()
    {
        if (_size.get() == 0)
            return null;

        E e = null;
        boolean wasFull = false;
        // Lock head, _size can grow but not shrink.
        try (var ignored = _headLock.lock())
        {
            if (_size.get() > 0)
            {
                e = lockedTakeFromHead();
                wasFull = lockedDecrementSize();
            }
        }

        if (wasFull)
            signal(_tailLock);

        return e;
    }

    @Override
    public E poll(long time, TimeUnit unit) throws InterruptedException
    {
        E e;
        boolean wasFull;
        long nanos = unit.toNanos(time);
        // Lock head, _size can grow but not shrink.
        try (var headLock = _headLock.lockInterruptibly())
        {
            try
            {
                // Head is locked, _size update 0->1 would signal(),
                // but must wait for the head lock to be released.
                while (_size.get() == 0)
                {
                    if (nanos <= 0)
                        return null;
                    nanos = headLock.awaitNanos(nanos);
                }
            }
            catch (InterruptedException x)
            {
                headLock.signal();
                throw x;
            }

            e = lockedTakeFromHead();
            wasFull = lockedDecrementSize();
        }

        if (wasFull)
            signal(_tailLock);

        return e;
    }

    @SuppressWarnings("unchecked")
    @Override
    public E peek()
    {
        if (_size.get() == 0)
            return null;

        // Lock head, _size can grow but not shrink.
        try (var ignored = _headLock.lock())
        {
            E e = null;
            if (_size.get() > 0)
                e = (E)_elements[_indexes[HEAD_OFFSET]];
            return e;
        }
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
        // Full lock, _size cannot change.
        try (var tailLock = _tailLock.lock(); var headLock = _headLock.lock())
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

            int oldSize = _size.getAndDecrement();
            if (oldSize == _maxCapacity)
                tailLock.signal();
            if (oldSize > 1)
                headLock.signal();

            return old;
        }
    }

    @Override
    public boolean remove(Object o)
    {
        // Full lock, _size cannot change.
        try (var ignoredT = _tailLock.lock(); var ignoredH = _headLock.lock())
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

        boolean wasEmpty;
        // Lock tail, _size can shrink but cannot grow.
        try (var ignoredT = _tailLock.lock())
        {
            int size = _size.get();
            if (size >= _maxCapacity)
                return false;

            // Should we expand array?
            if (size == _elements.length)
            {
                // Full lock, _size cannot change.
                try (var ignoredH = _headLock.lock())
                {
                    // Recheck size under both locks.
                    size = _size.get();
                    if (size == _elements.length)
                        lockedGrow();
                }
            }

            lockedAddToTail(e);
            wasEmpty = lockedIncrementSize();
        }

        if (wasEmpty)
            signal(_headLock);

        return true;
    }

    @Override
    public boolean offer(E o, long timeout, TimeUnit unit) throws InterruptedException
    {
        Objects.requireNonNull(o);

        boolean wasEmpty;
        // Lock tail, _size can shrink but cannot grow.
        try (var tailLock = _tailLock.lockInterruptibly())
        {
            long nanos = unit.toNanos(timeout);
            while (true)
            {
                int size = _size.get();
                // If we are full, then wait for space to become available.
                // Tail is locked, _size update max->max-1 would signal(),
                // but must wait for the tail lock to be released.
                if (size >= _maxCapacity)
                {
                    if (nanos <= 0L)
                        return false;
                    nanos = tailLock.awaitNanos(nanos);
                    // Recheck the size.
                    continue;
                }
                // Otherwise, can we grow?
                else if (size == _elements.length)
                {
                    // Full lock, _size cannot change.
                    try (var ignored = _headLock.lock())
                    {
                        // Recheck the size under both locks.
                        size = _size.get();
                        if (size == _elements.length)
                            lockedGrow();
                    }
                }

                // We can add now after a possible grow.
                lockedAddToTail(o);
                wasEmpty = lockedIncrementSize();
                break;
            }
        }

        if (wasEmpty)
            signal(_headLock);

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
    public void add(int index, E e)
    {
        if (e == null)
            throw new NullPointerException();

        // Full lock, _size cannot change.
        try (var tailLock = _tailLock.lock(); var headLock = _headLock.lock())
        {
            int size = _size.get();

            if (index < 0 || index > size)
                throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");

            if (index == size)
            {
                add(e);
            }
            else
            {
                if (_indexes[TAIL_OFFSET] == _indexes[HEAD_OFFSET])
                {
                    if (_size.get() == _maxCapacity)
                        throw new IllegalStateException("full");
                    lockedGrow();
                }

                // Re-read head and tail after a possible grow.
                int i = _indexes[HEAD_OFFSET] + index;
                int capacity = _elements.length;

                if (i >= capacity)
                    i -= capacity;

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

                size = _size.incrementAndGet();
                if (size >= 1)
                    headLock.signal();
                if (size < _maxCapacity)
                    tailLock.signal();
            }
        }
    }

    @Override
    public void put(E e) throws InterruptedException
    {
        Objects.requireNonNull(e);

        boolean wasEmpty;
        // Lock tail, _size can shrink but cannot grow.
        try (var tailLock = _tailLock.lockInterruptibly())
        {
            while (true)
            {
                int size = _size.get();

                // If we are full, then wait for space to become available.
                // Tail is locked, _size update max->max-1 would signal(),
                // but must wait for the tail lock to be released.
                if (size >= _maxCapacity)
                {
                    tailLock.await();
                    // Recheck the size.
                    continue;
                }
                // Otherwise, can we grow?
                else if (size == _elements.length)
                {
                    // Full lock, _size cannot change.
                    try (var ignored = _headLock.lock())
                    {
                        // Recheck the size under both locks.
                        size = _size.get();
                        if (size == _elements.length)
                            lockedGrow();
                    }
                }

                // We can add now after a possible grow.
                lockedAddToTail(e);
                wasEmpty = lockedIncrementSize();
                break;
            }
        }

        if (wasEmpty)
            signal(_headLock);
    }

    @Override
    public E take() throws InterruptedException
    {
        E e;
        boolean wasFull;
        // Lock head, _size can grow but cannot shrink.
        try (var headLock = _headLock.lockInterruptibly())
        {
            try
            {
                // Head is locked, _size update 0->1 would signal(),
                // but must wait for the head lock to be released.
                while (_size.get() == 0)
                {
                    headLock.await();
                }
            }
            catch (InterruptedException ex)
            {
                headLock.signal();
                throw ex;
            }

            e = lockedTakeFromHead();
            wasFull = lockedDecrementSize();
        }

        if (wasFull)
            signal(_tailLock);

        return e;
    }

    @Override
    public int remainingCapacity()
    {
        int maxCapacity = getMaxCapacity();
        if (maxCapacity == Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        return maxCapacity - size();
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
        // Full lock, _size cannot change.
        try (var tailLock = _tailLock.lock(); var ignored = _headLock.lock())
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
                @SuppressWarnings("unchecked")
                E e = (E)_elements[i];
                c.add(e);
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

            tailLock.signalAll();
        }
        return elements;
    }

    /* List methods */

    @SuppressWarnings("unchecked")
    @Override
    public E get(int index)
    {
        // Full lock, _size cannot change.
        try (var ignoredT = _tailLock.lock(); var ignoredH = _headLock.lock())
        {
            if (index < 0 || index >= _size.get())
                throw new IndexOutOfBoundsException("!(" + 0 + "<" + index + "<=" + _size + ")");
            int i = _indexes[HEAD_OFFSET] + index;
            int capacity = _elements.length;
            if (i >= capacity)
                i -= capacity;
            return (E)_elements[i];
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public E set(int index, E e)
    {
        Objects.requireNonNull(e);

        // Full lock, _size cannot change.
        try (var ignoredT = _tailLock.lock(); var ignoredH = _headLock.lock())
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
    }

    @Override
    public ListIterator<E> listIterator(int index)
    {
        // Full lock, _size cannot change.
        try (var ignoredT = _tailLock.lock(); var ignoredH = _headLock.lock())
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
    }

    /**
     * Get the current capacity of this queue.
     * @return the current capacity of this queue
     */
    public int getCapacity()
    {
        // Lock tail, capacity cannot grow.
        try (var ignored = _tailLock.lock())
        {
            return _elements.length;
        }
    }

    /**
     * Get the max capacity of this queue.
     * @return the max capacity of this queue
     */
    public int getMaxCapacity()
    {
        return _maxCapacity;
    }

    private void lockedGrow()
    {
        assert _tailLock.isHeldByCurrentThread();
        assert _headLock.isHeldByCurrentThread();
        final int head = _indexes[HEAD_OFFSET];
        final int tail = _indexes[TAIL_OFFSET];
        final int newTail;
        final int capacity = _elements.length;

        Object[] elements = new Object[ArrayUtil.growCapacity(capacity, 1, _maxCapacity)];

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
    }

    private void signal(AutoLock.WithCondition lock)
    {
        try (var ignored = lock.lock())
        {
            lock.signal();
        }
    }

    /**
     * Add an element to the tail of the queue with the tail lock held.
     * The size must be less than {@code _elements.length} when this method is called.
     * @param e the element to add
     */
    private void lockedAddToTail(E e)
    {
        assert _tailLock.isHeldByCurrentThread();
        int tail = _indexes[TAIL_OFFSET];
        _elements[tail] = e;
        _indexes[TAIL_OFFSET] = (tail + 1) % _elements.length;
    }

    /**
     * Take an element from the head of the queue with the head lock held.
     * The size must be greater than zero when this method is called.
     * @return the head element
     */
    private E lockedTakeFromHead()
    {
        assert _headLock.isHeldByCurrentThread();
        final int head = _indexes[HEAD_OFFSET];
        @SuppressWarnings("unchecked")
        E e = (E)_elements[head];
        _elements[head] = null;
        _indexes[HEAD_OFFSET] = (head + 1) % _elements.length;
        return e;
    }

    /**
     * <p>Increments the size under the tail lock.</p>
     * <p>Signals threads waiting on the tail lock if the new size remains less than max capacity.</p>
     *
     * @return true if the queue was empty prior to the size increment, false otherwise
     */
    private boolean lockedIncrementSize()
    {
        assert _tailLock.isHeldByCurrentThread();
        boolean wasEmpty;
        int oldSize = _size.getAndIncrement();
        wasEmpty = oldSize == 0;
        if (oldSize + 1 < _maxCapacity)
            _tailLock.signal();
        return wasEmpty;
    }

    /**
     * <p>Decrements the size under the head lock.</p>
     * <p>Signals threads waiting on the head lock if the new size remains greater than zero.</p>
     *
     * @return true if the queue was full prior to the size decrement, false otherwise
     */
    private boolean lockedDecrementSize()
    {
        assert _headLock.isHeldByCurrentThread();
        boolean wasFull;
        int oldSize = _size.getAndDecrement();
        wasFull = oldSize == _maxCapacity;
        if (oldSize > 1)
            _headLock.signal();
        return wasFull;
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
