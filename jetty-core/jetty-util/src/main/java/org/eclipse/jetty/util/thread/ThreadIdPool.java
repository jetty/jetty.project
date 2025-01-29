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

package org.eclipse.jetty.util.thread;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.MemoryUtils;
import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.component.Dumpable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fixed sized pool of items that uses ThreadId to avoid contention.
 * This class can be used, instead of a {@link ThreadLocal}, when pooling items
 * that are expensive to create, but only used briefly in the scope of a single thread.
 * It is safe to use with {@link org.eclipse.jetty.util.VirtualThreads}, as unlike a {@link ThreadLocal} pool,
 * the number of items is limited.
 * <p>This is a light-weight version of {@link org.eclipse.jetty.util.ConcurrentPool} that is best used
 * when items do not reserve an index in the pool even when acquired.
 * @see org.eclipse.jetty.util.ConcurrentPool
 */
public class ThreadIdPool<E> implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ThreadIdPool.class);

    // How far the entries in the AtomicReferenceArray are spread apart to avoid false sharing.
    private static final int SPREAD_FACTOR = MemoryUtils.getReferencesPerCacheLine();

    private final int _capacity;
    private final AtomicReferenceArray<E> _items;

    public ThreadIdPool()
    {
        this(-1);
    }

    public ThreadIdPool(int capacity)
    {
        _capacity = calcCapacity(capacity);
        _items = new AtomicReferenceArray<>((_capacity + 1) * SPREAD_FACTOR);
        if (LOG.isDebugEnabled())
            LOG.debug("{}", this);
    }

    private static int calcCapacity(int capacity)
    {
        if (capacity >= 0)
            return capacity;
        return 2 * MathUtils.ceilToNextPowerOfTwo(ProcessorUtils.availableProcessors());
    }

    private static int toSlot(int index)
    {
        return (index + 1) * SPREAD_FACTOR;
    }
    
    /**
     * @return the maximum number of items
     */
    public int capacity()
    {
        return _capacity;
    }

    /**
     * @return the number of items available
     */
    public int size()
    {
        int available = 0;
        for (int i = 0; i < capacity(); i++)
        {
            if (_items.getPlain(toSlot(i)) != null)
                available++;
        }
        return available;
    }

    /**
     * Offer an item to the pool.
     * @param e The item to offer
     * @return The index the item was added at or -1, if it was not added
     * @see #remove(Object, int) 
     */
    public int offer(E e)
    {
        int capacity = capacity();
        if (capacity > 0)
        {
            int index = (int)(Thread.currentThread().getId() % capacity);
            for (int i = 0; i < capacity; i++)
            {
                if (_items.compareAndSet(toSlot(index), null, e))
                    return index;
                if (++index == capacity)
                    index = 0;
            }
        }
        return -1;
    }

    /**
     * Take an item from the pool.
     * @return The taken item or null if none available.
     */
    public E take()
    {
        int capacity = capacity();
        if (capacity == 0)
            return null;
        int index = (int)(Thread.currentThread().getId() % capacity);
        for (int i = 0; i < capacity; i++)
        {
            E e = _items.getAndSet(toSlot(index), null);
            if (e != null)
                return e;
            if (++index == capacity)
                index = 0;
        }
        return null;
    }

    /**
     * Remove a specific item from the pool from a specific index 
     * @param e The item to remove
     * @param index The index the item was given to, as returned by {@link #offer(Object)}
     * @return {@code True} if the item was in the pool and was able to be removed.
     */
    public boolean remove(E e, int index)
    {
        if (index < 0)
            throw new IndexOutOfBoundsException();
        return _items.compareAndSet(toSlot(index), e, null);
    }

    /**
     * Removes all items from the pool.
     * @return A list of all removed items
     */
    public List<E> removeAll()
    {
        int capacity = capacity();
        List<E> all = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++)
        {
            E e = _items.getAndSet(toSlot(i), null);
            if (e != null)
                all.add(e);
        }
        return all;
    }

    /**
     * Take an item with a {@link #take()} operation, else if that returns null then use the {@code supplier} (which may
     * construct a new instance).
     * @param supplier The supplier for an item to be used if an item cannot be taken from the pool.
     * @return An item, never null.
     */
    public E takeOrElse(Supplier<E> supplier)
    {
        E e = take();
        return e == null ? supplier.get() : e;
    }

    /**
     * Apply an item, either from the pool or supplier, to a function, then give it back to the pool.
     * This is equivalent of {@link #takeOrElse(Supplier)}; then {@link Function#apply(Object)};
     * followed by {@link #offer(Object)}.
     * @param supplier The supplier for an item to be used if an item cannot be taken from the pool.
     * @param function A function producing a result from an item.  This may be
     *                 a method reference to a method on the item taking no arguments and producing a result.
     * @param <R> The type of the function return
     * @return Te result of the function applied to the item and the argument
     */
    public <R> R apply(Supplier<E> supplier, Function<E, R> function)
    {
        E e = takeOrElse(supplier);
        try
        {
            return function.apply(e);
        }
        finally
        {
            offer(e);
        }
    }

    /**
     * Apply an item, either from the pool or supplier, to a function, then give it back to the pool.
     * This is equivalent of {@link #takeOrElse(Supplier)}; then {@link BiFunction#apply(Object, Object)};
     * followed by {@link #offer(Object)}.
     * @param supplier The supplier for an item to be used if an item cannot be taken from the pool.
     * @param function A function producing a result from an item and an argument.  This may be
     *                 a method reference to a method on the item taking an argument and producing a result.
     * @param argument The argument to pass to the function.
     * @param <A> The type of the function argument
     * @param <R> The type of the function return
     * @return Te result of the function applied to the item and the argument
     */
    public <A, R> R apply(Supplier<E> supplier, BiFunction<E, A, R> function, A argument)
    {
        E e = takeOrElse(supplier);
        try
        {
            return function.apply(e, argument);
        }
        finally
        {
            offer(e);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        int capacity = capacity();
        List<Object> slots = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++)
        {
            E slot = _items.get(toSlot(i));
            if (slot != null)
                slots.add(Dumpable.named(Integer.toString(i), slot));
        }
        Dumpable.dumpObjects(out, indent, this, slots.toArray());
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{capacity=%d}",
            getClass().getSimpleName(),
            hashCode(),
            capacity());
    }
}
