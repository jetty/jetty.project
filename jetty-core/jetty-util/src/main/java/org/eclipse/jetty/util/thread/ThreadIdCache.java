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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fixed sized cache of items that uses ThreadId to avoid contention.
 * This class can be used, instead of a {@link ThreadLocal}, when caching items
 * that are expensive to create, but only used briefly in the scope of a single thread.
 * It is safe to use with {@link org.eclipse.jetty.util.VirtualThreads}, as unlike a {@link ThreadLocal} cache,
 * the number of items is limited.
 */
public class ThreadIdCache<E> implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ThreadIdCache.class);

    private final AtomicReferenceArray<E> _items;

    public ThreadIdCache()
    {
        this(-1);
    }

    public ThreadIdCache(int capacity)
    {
        _items = new AtomicReferenceArray<>(calcCapacity(capacity));
        if (LOG.isDebugEnabled())
            LOG.debug("{}", this);
    }

    private static int calcCapacity(int capacity)
    {
        if (capacity >= 0)
            return capacity;
        return 2 * ProcessorUtils.availableProcessors();
    }

    /**
     * @return the maximum number of items
     */
    public int capacity()
    {
        return _items.length();
    }

    /**
     * @return the number of items available
     */
    public int size()
    {
        int available = 0;
        for (int i = _items.length(); i-- > 0; )
            if (_items.get(i) != null)
                available++;
        return available;
    }

    /**
     * Give an item to the cache.
     * @param e The item to give
     * @return The index the item was added at or -1, if it was not added
     * @see #take(Object, int) 
     */
    public int give(E e)
    {
        int capacity = capacity();
        int index = (int)(Thread.currentThread().getId() % capacity);
        for (int i = capacity; i-- > 0; )
        {
            if (_items.compareAndSet(index, null, e))
                return index;
            if (++index == capacity)
                index = 0;
        }
        return -1;
    }

    /**
     * Take an item from the cache.
     * @return The taken item or null if none available.
     */
    public E take()
    {
        int capacity = capacity();
        int index = (int)(Thread.currentThread().getId() % capacity);
        for (int i = capacity; i-- > 0;)
        {
            E e = _items.getAndSet(index, null);
            if (e != null)
                return e;
            if (++index == capacity)
                index = 0;
        }
        return null;
    }

    /**
     * Take a specific item from the cache 
     * @param e The item to take
     * @param index The index the item was given to, as returned by {@link #give(Object)}
     * @return {@code True} if the item was in the cache and was able to be removed.
     */
    public boolean take(E e, int index)
    {
        return _items.compareAndSet(index, e, null);
    }

    /**
     * Take all items from the cache.
     * @return A list of all taken items
     */
    public List<E> takeAll()
    {
        List<E> all = new ArrayList<>(capacity());
        for (int i = capacity(); i-- > 0;)
        {
            E e = _items.getAndSet(i, null);
            if (e != null)
                all.add(e);
        }
        return all;
    }

    /**
     * Get an item, either by {@link #take() taking} it from the cache or from the passed supplier (which may
     * construct a new instance).
     * @param supplier The supplier for an item to be used if an item cannot be taken from the cache.
     * @return An item, never null.
     */
    public E get(Supplier<E> supplier)
    {
        E e = take();
        return e == null ? supplier.get() : e;
    }

    /**
     * Use an item, either from the cache or supplier, with a function, then give it back to the cache.
     * @param supplier The supplier for an item to be used if an item cannot be taken from the cache.
     * @param function A function producing a result from an item.  This may be
     *                 a method reference to a method on the item taking no arguments and producing a result.
     * @param <R> The type of the function return
     * @return Te result of the function applied to the item and the argument
     */
    public <R> R useWith(Supplier<E> supplier, Function<E, R> function)
    {
        E e = get(supplier);
        try
        {
            return function.apply(e);
        }
        finally
        {
            give(e);
        }
    }

    /**
     * Use an item, either from the cache or supplier, with a function, then give it back to the cache.
     * @param supplier The supplier for an item to be used if an item cannot be taken from the cache.
     * @param function A function producing a result from an item and an argument.  This may be
     *                 a method reference to a method on the item taking an argument and producing a result.
     * @param argument The argument to pass to the function.
     * @param <A> The type of the function argument
     * @param <R> The type of the function return
     * @return Te result of the function applied to the item and the argument
     */
    public <A, R> R useWith(Supplier<E> supplier, BiFunction<E, A, R> function, A argument)
    {
        E e = get(supplier);
        try
        {
            return function.apply(e, argument);
        }
        finally
        {
            give(e);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        int capacity = capacity();
        List<Object> slots = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++)
            slots.add(_items.get(i));
        Dumpable.dumpObjects(out, indent, this, new DumpableCollection("items", slots));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{capacity=%d}",
            getClass().getSimpleName(),
            hashCode(),
            capacity());
    }

    /**
     * A replacement for {@link java.util.concurrent.ThreadLocalRandom} based on an internal
     * {@link ThreadIdCache}, so it is safe to use with unlimited {@link org.eclipse.jetty.util.VirtualThreads}.
     * Calls like {@code ThreadIdCache.Random.instance().nextInt()} can be replaced with
     * {@code ThreadIdCache.Random.instance().nextInt()}.
     */
    public static class Random implements RandomGenerator
    {
        private static final Random INSTANCE = new Random();

        public static RandomGenerator instance()
        {
            return INSTANCE;
        }

        public static RandomGenerator threadLocalOrInstance()
        {
            if (!VirtualThreads.isVirtualThread())
                return ThreadLocalRandom.current();
            return INSTANCE;
        }

        private final ThreadIdCache<java.util.Random> _cache = new ThreadIdCache<>();

        private Random()
        {
        }

        @Override
        public long nextLong()
        {
            return _cache.useWith(java.util.Random::new, java.util.Random::nextLong);
        }
    }
}
