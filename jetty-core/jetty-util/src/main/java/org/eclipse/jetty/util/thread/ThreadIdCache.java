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
import java.util.function.Supplier;

import org.eclipse.jetty.util.ProcessorUtils;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fixed sized cache of instances that uses ThreadId to avoid contention
 */
@ManagedObject("A pool for reserved threads")
public class ThreadIdCache<E> implements Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ThreadIdCache.class);

    private final AtomicReferenceArray<E> _entries;

    public ThreadIdCache()
    {
        this(-1);
    }

    public ThreadIdCache(int capacity)
    {
        _entries = new AtomicReferenceArray<>(calcCapacity(capacity));
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
     * @return the maximum number of entries
     */
    public int capacity()
    {
        return _entries.length();
    }

    /**
     * @return the number of entries available
     */
    public int size()
    {
        int available = 0;
        for (int i = _entries.length(); i-- > 0; )
            if (_entries.get(i) != null)
                available++;
        return available;
    }
    
    public int give(E e)
    {
        int capacity = capacity();
        int index = (int)(Thread.currentThread().getId() % capacity);
        for (int i = capacity; i-- > 0; )
        {
            if (_entries.compareAndSet(index, null, e))
                return index;
            if (++index == capacity)
                index = 0;
        }
        return -1;
    }

    public boolean remove(E e, int index)
    {
        return _entries.compareAndSet(index, e, null);
    }

    public E take()
    {
        int capacity = capacity();
        int index = (int)(Thread.currentThread().getId() % capacity);
        for (int i = capacity; i-- > 0;)
        {
            E e = _entries.getAndSet(index, null);
            if (e != null)
                return e;
            if (++index == capacity)
                index = 0;
        }
        return null;
    }

    public List<E> takeAll()
    {
        List<E> all = new ArrayList<>(capacity());
        for (int i = capacity(); i-- > 0;)
        {
            E e = _entries.getAndSet(i, null);
            if (e != null)
                all.add(e);
        }
        return all;
    }

    public E get(Supplier<E> constructor)
    {
        E e = take();
        return e == null ? constructor.get() : e;
    }

    public <T, R> R with(Supplier<E> constructor, BiFunction<E, T, R> consumer, T t)
    {
        E e = get(constructor);
        try
        {
            return consumer.apply(e, t);
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
            slots.add(_entries.get(i));
        Dumpable.dumpObjects(out, indent, this, new DumpableCollection("slots", slots));
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
