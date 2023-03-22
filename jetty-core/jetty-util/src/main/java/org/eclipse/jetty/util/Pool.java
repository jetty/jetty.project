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

import java.util.Collection;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * <p>A pool of objects, with support for multiplexing and several
 * optimized strategies plus an optional {@link ThreadLocal} cache
 * of the last released entry.</p>
 * <p>A {@code Pool} should be {@link #terminate() terminated} when
 * it is no longer needed; once terminated, it cannot be used anymore.</p>
 *
 * @param <P> the type of the pooled objects
 */
@ManagedObject
public interface Pool<P>
{
    /**
     * <p>Creates a new disabled slot into the pool.</p>
     * <p>The returned entry must ultimately have the {@link Entry#enable(Object, boolean)}
     * method called or be removed via {@link Pool.Entry#remove()}.</p>
     *
     * @return a disabled entry that is contained in the pool, or {@code null}
     * if the pool is terminated or if the pool cannot reserve an entry
     */
    Entry<P> reserve();

    /**
     * <p>Acquires an entry from the pool.</p>
     * <p>Only enabled entries will be returned from this method
     * and their {@link Entry#enable(Object, boolean)}
     * method must not be called.</p>
     *
     * @return an entry from the pool or null if none is available.
     */
    Entry<P> acquire();

    /**
     * <p>Acquires an entry from the pool,
     * reserving and creating a new entry if necessary.</p>
     *
     * @param creator a function to create the pooled value for a reserved entry.
     * @return an entry from the pool or null if none is available.
     */
    default Entry<P> acquire(Function<Entry<P>, P> creator)
    {
        Entry<P> entry = acquire();
        if (entry != null)
            return entry;

        entry = reserve();
        if (entry == null)
            return null;

        P value;
        try
        {
            value = creator.apply(entry);
        }
        catch (Throwable th)
        {
            entry.remove();
            throw th;
        }

        if (value == null)
        {
            entry.remove();
            return null;
        }

        return entry.enable(value, true) ? entry : null;
    }

    /**
     * @return whether this {@code Pool} has been terminated
     * @see #terminate()
     */
    boolean isTerminated();

    /**
     * <p>Terminates this {@code Pool}.</p>
     * <p>All the entries are marked as terminated and cannot be
     * acquired nor released, but only removed.</p>
     * <p>The returned list of all entries may be iterated to
     * perform additional operations on the pooled objects.</p>
     * <p>The pool cannot be used anymore after it is terminated.</p>
     *
     * @return a list of all entries
     */
    Collection<Entry<P>> terminate();

    /**
     * @return the current number of entries in this {@code Pool}
     */
    @ManagedAttribute("The number of entries")
    int size();

    /**
     * @return the maximum number of entries in this {@code Pool}
     */
    @ManagedAttribute("The maximum number of entries")
    int getMaxSize();

    /**
     * @return a {@link Stream} over the entries
     */
    Stream<Entry<P>> stream();

    /**
     * @return the number of reserved entries
     */
    @ManagedAttribute("The number of reserved entries")
    default int getReservedCount()
    {
        return (int)stream().filter(Entry::isReserved).count();
    }

    /**
     * @return the number of idle entries
     */
    @ManagedAttribute("The number of idle entries")
    default int getIdleCount()
    {
        return (int)stream().filter(Entry::isIdle).count();
    }

    /**
     * @return the number of in-use entries
     */
    @ManagedAttribute("The number of in-use entries")
    default int getInUseCount()
    {
        return (int)stream().filter(Entry::isInUse).count();
    }

    /**
     * @return the number of terminated entries
     */
    @ManagedAttribute("The number of terminated entries")
    default int getTerminatedCount()
    {
        return (int)stream().filter(Entry::isTerminated).count();
    }

    /**
     * <p>A wrapper for {@code Pool} instances.</p>
     *
     * @param <W> the type of the pooled objects
     */
    class Wrapper<W> implements Pool<W>
    {
        private final Pool<W> wrapped;

        public Wrapper(Pool<W> wrapped)
        {
            this.wrapped = wrapped;
        }

        public Pool<W> getWrapped()
        {
            return wrapped;
        }

        @Override
        public Entry<W> reserve()
        {
            return getWrapped().reserve();
        }

        @Override
        public Entry<W> acquire()
        {
            return getWrapped().acquire();
        }

        @Override
        public Entry<W> acquire(Function<Entry<W>, W> creator)
        {
            return getWrapped().acquire(creator);
        }

        @Override
        public boolean isTerminated()
        {
            return getWrapped().isTerminated();
        }

        @Override
        public Collection<Entry<W>> terminate()
        {
            return getWrapped().terminate();
        }

        @Override
        public int size()
        {
            return getWrapped().size();
        }

        @Override
        public int getMaxSize()
        {
            return getWrapped().getMaxSize();
        }

        @Override
        public Stream<Entry<W>> stream()
        {
            return getWrapped().stream();
        }

        @Override
        public int getReservedCount()
        {
            return getWrapped().getReservedCount();
        }

        @Override
        public int getIdleCount()
        {
            return getWrapped().getIdleCount();
        }

        @Override
        public int getInUseCount()
        {
            return getWrapped().getInUseCount();
        }

        @Override
        public int getTerminatedCount()
        {
            return getWrapped().getTerminatedCount();
        }
    }

    /**
     * <p>A {@code Pool} entry that holds metadata and a pooled object.</p>
     *
     * @param <E> the type of the pooled objects
     */
    interface Entry<E>
    {
        /**
         * <p>Enables this, previously {@link #reserve() reserved}, {@code Entry}.</p>
         * <p>An entry returned from the {@link #reserve()} method must be enabled
         * with this method, once and only once, before it is usable by the pool.</p>
         * <p>The entry may be enabled and not acquired, in which case it is immediately
         * available to be acquired, potentially by another thread; or it can be enabled
         * and acquired atomically so that no other thread can acquire it, although the
         * acquire may still fail if the pool has been terminated.</p>
         *
         * @param pooled the pooled object for this {@code Entry}
         * @param acquire whether this {@code Entry} should be atomically enabled and acquired
         * @return whether this {@code Entry} was enabled
         * @throws IllegalStateException if this {@code Entry} was already enabled
         */
        boolean enable(E pooled, boolean acquire);

        /**
         * @return the pooled object
         */
        E getPooled();

        /**
         * <p>Releases this {@code Entry} to the {@code Pool}.</p>
         *
         * @return whether this {@code Entry} was released
         */
        boolean release();

        /**
         * <p>Removes this {@code Entry} from the {@code Pool}.</p>
         *
         * @return whether this {@code Entry} was removed
         */
        boolean remove();

        /**
         * @return whether this {@code Entry} is reserved
         * @see Pool#reserve()
         */
        boolean isReserved();

        /**
         * @return whether this {@code Entry} is idle in the {@code Pool}
         */
        boolean isIdle();

        /**
         * @return whether this {@code Entry} is in use
         */
        boolean isInUse();

        /**
         * @return whether this {@code Entry} is terminated
         */
        boolean isTerminated();

        /**
         * <p>A wrapper for {@code Entry} instances.</p>
         *
         * @param <W> the type of the pooled objects
         */
        class Wrapper<W> implements Entry<W>
        {
            private final Entry<W> wrapped;

            public Wrapper(Entry<W> wrapped)
            {
                this.wrapped = wrapped;
            }

            public Entry<W> getWrapped()
            {
                return wrapped;
            }

            @Override
            public boolean enable(W pooled, boolean acquire)
            {
                return getWrapped().enable(pooled, acquire);
            }

            @Override
            public W getPooled()
            {
                return getWrapped().getPooled();
            }

            @Override
            public boolean release()
            {
                return getWrapped().release();
            }

            @Override
            public boolean remove()
            {
                return getWrapped().remove();
            }

            @Override
            public boolean isReserved()
            {
                return getWrapped().isReserved();
            }

            @Override
            public boolean isIdle()
            {
                return getWrapped().isIdle();
            }

            @Override
            public boolean isInUse()
            {
                return getWrapped().isInUse();
            }

            @Override
            public boolean isTerminated()
            {
                return getWrapped().isTerminated();
            }
        }
    }

    /**
     * <p>A factory for {@link Pool} instances.</p>
     *
     * @param <F> the type of the pooled objects
     */
    interface Factory<F>
    {
        /**
         * @return a new {@link Pool} instance
         */
        public Pool<F> newPool();

        /**
         * <p>Wraps, if necessary, the given pool.</p>
         *
         * @param pool the pool to wrap
         * @return a possibly wrapped pool
         * @see Pool.Wrapper
         */
        public default Pool<F> wrap(Pool<F> pool)
        {
            return pool;
        }
    }
}
