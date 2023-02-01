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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.thread.AutoLock;

/**
 * <p>A {@link Pool.Wrapper} that tracks the acquire/release/remove pool events.</p>
 * <p>The acquire/release/remove pool events are forwarded atomically to be handled
 * by a {@link Tracker} implementation, so that the pool event and the handling of
 * the event by the tracker are atomic.</p>
 *
 * @param <P> the type of the pooled objects
 */
public class LockedPool<P> extends Pool.Wrapper<P>
{
    private final AutoLock lock = new AutoLock();
    private final Tracker<P> tracker;

    public LockedPool(Pool<P> pool)
    {
        this(pool, Tracker.noTracker());
    }

    public LockedPool(Pool<P> pool, Tracker<P> tracker)
    {
        super(pool);
        this.tracker = tracker;
    }

    @Override
    public Entry<P> reserve()
    {
        try (AutoLock ignored = lock.lock())
        {
            Entry<P> entry = super.reserve();
            if (entry == null)
                return null;
            return new LockedEntry(entry);
        }
    }

    @Override
    public Entry<P> acquire()
    {
        try (AutoLock ignored = lock.lock())
        {
            Entry<P> entry = super.acquire();
            if (entry == null)
                return null;
            LockedEntry lockedEntry = new LockedEntry(entry);
            tracker.acquired(getWrapped(), entry);
            return lockedEntry;
        }
    }

    @Override
    public Entry<P> acquire(Function<Entry<P>, P> creator)
    {
        try (AutoLock ignored = lock.lock())
        {
            Entry<P> entry = super.acquire(creator);
            if (entry == null)
                return null;
            LockedEntry lockedEntry = new LockedEntry(entry);
            tracker.acquired(getWrapped(), entry);
            return lockedEntry;
        }
    }

    @Override
    public boolean isTerminated()
    {
        try (AutoLock ignored = lock.lock())
        {
            return super.isTerminated();
        }
    }

    @Override
    public Collection<Entry<P>> terminate()
    {
        try (AutoLock ignored = lock.lock())
        {
            Collection<Entry<P>> result = super.terminate();
            tracker.terminated(getWrapped(), result);
            return result.stream()
                .map(LockedEntry::new)
                .collect(Collectors.toList());
        }
    }

    @Override
    public int size()
    {
        try (AutoLock ignored = lock.lock())
        {
            return super.size();
        }
    }

    @Override
    public int getMaxSize()
    {
        try (AutoLock ignored = lock.lock())
        {
            return super.getMaxSize();
        }
    }

    @Override
    public Stream<Entry<P>> stream()
    {
        try (AutoLock ignored = lock.lock())
        {
            return super.stream().map(LockedEntry::new);
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
                if (released)
                    tracker.released(LockedPool.this.getWrapped(), getWrapped());
                return released;
            }
        }

        @Override
        public boolean remove()
        {
            try (AutoLock ignored = lock.lock())
            {
                boolean removed = super.remove();
                if (removed)
                    tracker.removed(LockedPool.this.getWrapped(), getWrapped());
                return removed;
            }
        }
    }

    /**
     * <p>A {@link Pool.Factory} that wraps newly created
     * {@link Pool} instances with {@link LockedPool}.</p>
     *
     * @param <F> the type of pooled objects
     */
    public interface Factory<F> extends Pool.Factory<F>
    {
        @Override
        public default Pool<F> wrap(Pool<F> pool)
        {
            return new LockedPool<>(pool);
        }
    }

    /**
     * <p>A receiver of {@link Pool} events.</p>
     * <p>A simple implementations may just count acquire/release/remove
     * pool events via, respectively, {@link #acquired(Pool, Entry)},
     * {@link #released(Pool, Entry)} and {@link #removed(Pool, Entry)},
     * and make sure that the count is {@code 0} when
     * {@link #terminated(Pool, Collection)} is called.</p>
     * <p>More advanced implementations may also obtain a stack trace at
     * the time of the event to troubleshoot leaking of pooled entries.</p>
     *
     * @param <T> the type of pooled objects
     */
    public interface Tracker<T>
    {
        /**
         * @return a no-op implementation of {@code Tracker}
         * @param <S> the type of pooled objects
         */
        @SuppressWarnings("unchecked")
        public static <S> Tracker<S> noTracker()
        {
            class NoTracker implements Tracker<S>
            {
                private static final Tracker<?> INSTANCE = new NoTracker();
            }

            return (Tracker<S>)NoTracker.INSTANCE;
        }

        /**
         * <p>Callback method invoked when an entry is
         * {@link Pool#acquire() acquired}.</p>
         *
         * @param pool the pool
         * @param entry the acquired entry
         */
        public default void acquired(Pool<T> pool, Entry<T> entry)
        {
        }

        /**
         * <p>Callback method invoked when an entry is
         * {@link Entry#release() released}.</p>
         *
         * @param pool the pool
         * @param entry the released entry
         */
        public default void released(Pool<T> pool, Entry<T> entry)
        {
        }

        /**
         * <p>Callback method invoked when an entry is
         * {@link Entry#remove() removed}.</p>
         *
         * @param pool the pool
         * @param entry the removed entry
         */
        public default void removed(Pool<T> pool, Entry<T> entry)
        {
        }

        /**
         * <p>Callback method invoked when the {@code Pool}
         * is {@link Pool#terminate() terminated}.</p>
         *
         * @param pool the pool
         * @param entries the list of entries at termination
         */
        public default void terminated(Pool<T> pool, Collection<Entry<T>> entries)
        {
        }
    }
}
