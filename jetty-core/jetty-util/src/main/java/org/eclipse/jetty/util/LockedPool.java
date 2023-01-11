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

import java.util.Collection;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import org.eclipse.jetty.util.thread.AutoLock;

public class LockedPool<P> implements Pool<P>
{
    private final AutoLock lock = new AutoLock();
    private final Pool<P> pool;
    private final Tracker<P> tracker;

    public LockedPool(StrategyType strategyType, int maxEntries, boolean cache, ToIntFunction<P> maxMultiplex)
    {
        this(strategyType, maxEntries, cache, maxMultiplex, Tracker.noTracker());
    }

    public LockedPool(StrategyType strategyType, int maxEntries, boolean cache, ToIntFunction<P> maxMultiplex, Tracker<P> tracker)
    {
        this.pool = new ConcurrentPool<>(strategyType, maxEntries, cache, maxMultiplex);
        this.tracker = tracker;
    }

    @Override
    public Entry<P> reserve()
    {
        try (AutoLock ignored = lock.lock())
        {
            Entry<P> entry = pool.reserve();
            return new LockedEntry(entry);
        }
    }

    @Override
    public Entry<P> acquire()
    {
        try (AutoLock ignored = lock.lock())
        {
            Entry<P> entry = pool.acquire();
            LockedEntry lockedEntry = new LockedEntry(entry);
            tracker.acquired(entry);
            return lockedEntry;
        }
    }

    @Override
    public Entry<P> acquire(Function<Entry<P>, P> creator)
    {
        try (AutoLock ignored = lock.lock())
        {
            Entry<P> entry = pool.acquire(creator);
            LockedEntry lockedEntry = new LockedEntry(entry);
            tracker.acquired(entry);
            return lockedEntry;
        }
    }

    @Override
    public boolean isTerminated()
    {
        try (AutoLock ignored = lock.lock())
        {
            return pool.isTerminated();
        }
    }

    @Override
    public Collection<Entry<P>> terminate()
    {
        try (AutoLock ignored = lock.lock())
        {
            Collection<Entry<P>> result = pool.terminate();
            tracker.terminated();
            return result;
        }
    }

    @Override
    public int size()
    {
        try (AutoLock ignored = lock.lock())
        {
            return pool.size();
        }
    }

    @Override
    public int getMaxSize()
    {
        try (AutoLock ignored = lock.lock())
        {
            return pool.getMaxSize();
        }
    }

    @Override
    public Stream<Entry<P>> stream()
    {
        try (AutoLock ignored = lock.lock())
        {
            return pool.stream();
        }
    }

    private class LockedEntry extends Entry.Wrapper<P>
    {
        public LockedEntry(Entry<P> wrapped)
        {
            super(wrapped);
        }

        @Override
        public boolean release()
        {
            try (AutoLock ignored = lock.lock())
            {
                boolean released = super.release();
                tracker.released(getWrapped());
                return released;
            }
        }

        @Override
        public boolean remove()
        {
            try (AutoLock ignored = lock.lock())
            {
                boolean removed = super.remove();
                tracker.removed(getWrapped());
                return removed;
            }
        }
    }

    public interface Tracker<T>
    {
        @SuppressWarnings("unchecked")
        public static <S> Tracker<S> noTracker()
        {
            class NoTracker implements Tracker<S>
            {
                private static final Tracker<?> INSTANCE = new NoTracker();
            }

            return (Tracker<S>)NoTracker.INSTANCE;
        }

        public default void acquired(Entry<T> entry)
        {
        }

        public default void released(Entry<T> entry)
        {
        }

        public default void removed(Entry<T> entry)
        {
        }

        public default void terminated()
        {
        }
    }
}
