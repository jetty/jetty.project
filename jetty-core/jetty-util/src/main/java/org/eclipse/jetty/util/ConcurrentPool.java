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

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A concurrent implementation of {@link Pool}.</p>
 * <p>This implementation offers a number of {@link StrategyType strategies}
 * used to select the entry returned from {@link #acquire()}, and its
 * capacity is bounded to a {@link #getMaxSize() max size}.</p>
 * <p>A thread-local caching is also available, disabled by default, that
 * is useful when entries are acquired and released by the same thread,
 * but hampering performance when entries are acquired by one thread but
 * released by a different thread.</p>
 *
 * @param <P> the type of the pooled objects
 */
@ManagedObject
public class ConcurrentPool<P> implements Pool<P>, Dumpable
{
    /**
     * {@link ConcurrentPool} internally needs to linearly scan a list to perform an acquisition.
     * This list needs to be reasonably short otherwise there is a risk that scanning the list
     * becomes a bottleneck. Instances created with a size at most this value should be immune
     * to this problem.
     */
    public static final int OPTIMAL_MAX_SIZE = 256;

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrentPool.class);

    private final List<Entry<P>> entries = new CopyOnWriteArrayList<>();
    private final int maxSize;
    private final StrategyType strategyType;
    /*
     * The cache is used to avoid hammering on the first index of the entry list.
     * Caches can become poisoned (i.e.: containing entries that are in use) when
     * the release isn't done by the acquiring thread or when the entry pool is
     * undersized compared to the load applied on it.
     * When an entry can't be found in the cache, the global list is iterated
     * with the configured strategy so the cache has no visible effect besides performance.
     */
    private final ThreadLocal<Entry<P>> cache;
    private final AutoLock lock = new AutoLock();
    private final AtomicInteger nextIndex;
    private final ToIntFunction<P> maxMultiplex;
    private volatile boolean terminated;

    /**
     * <p>Creates an instance with the specified strategy and no {@link ThreadLocal} cache.</p>
     *
     * @param strategyType the strategy to used to lookup entries
     * @param maxSize the maximum number of pooled entries
     */
    public ConcurrentPool(StrategyType strategyType, int maxSize)
    {
        this(strategyType, maxSize, false);
    }

    /**
     * <p>Creates an instance with the specified strategy and an optional {@link ThreadLocal} cache.</p>
     *
     * @param strategyType the strategy to used to lookup entries
     * @param maxSize the maximum number of pooled entries
     * @param cache whether a {@link ThreadLocal} cache should be used for the most recently released entry
     */
    public ConcurrentPool(StrategyType strategyType, int maxSize, boolean cache)
    {
        this(strategyType, maxSize, cache, pooled -> 1);
    }

    /**
     * <p>Creates an instance with the specified strategy, an optional {@link ThreadLocal} cache.
     * and a function that returns the max multiplex count for a given pooled object.</p>
     *
     * @param strategyType the strategy to used to lookup entries
     * @param maxSize the maximum number of pooled entries
     * @param cache whether a {@link ThreadLocal} cache should be used for the most recently released entry
     * @param maxMultiplex a function that given the pooled object returns the max multiplex count
     */
    public ConcurrentPool(StrategyType strategyType, int maxSize, boolean cache, ToIntFunction<P> maxMultiplex)
    {
        if (maxSize > OPTIMAL_MAX_SIZE && LOG.isDebugEnabled())
            LOG.debug("{} configured with max size {} which is above the recommended value {}", getClass().getSimpleName(), maxSize, OPTIMAL_MAX_SIZE);
        this.maxSize = maxSize;
        this.strategyType = Objects.requireNonNull(strategyType);
        this.cache = cache ? new ThreadLocal<>() : null;
        this.nextIndex = strategyType == StrategyType.ROUND_ROBIN ? new AtomicInteger() : null;
        this.maxMultiplex = Objects.requireNonNull(maxMultiplex);
    }

    public int getTerminatedCount()
    {
        return (int)entries.stream().filter(Entry::isTerminated).count();
    }

    /**
     * <p>Retrieves the max multiplex count for the given pooled object.</p>
     *
     * @param pooled the pooled object
     * @return the max multiplex count for the given pooled object
     */
    private int getMaxMultiplex(P pooled)
    {
        return maxMultiplex.applyAsInt(pooled);
    }

    @Override
    public Entry<P> reserve()
    {
        try (AutoLock ignored = lock.lock())
        {
            // Because the list of entries is modified, this.terminated
            // must be read with the lock held, see method terminate().
            if (terminated)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("terminated, cannot reserve entry for {}", this);
                return null;
            }

            // If we have no space
            int entriesSize = entries.size();
            if (maxSize > 0 && entriesSize >= maxSize)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("no space: {} >= {}, cannot reserve entry for {}", entriesSize, maxSize, this);
                return null;
            }

            ConcurrentEntry<P> entry = new ConcurrentEntry<>(this);
            entries.add(entry);
            if (LOG.isDebugEnabled())
                LOG.debug("returning reserved entry {} for {}", entry, this);
            return entry;
        }
    }

    @Override
    public Entry<P> acquire()
    {
        if (terminated)
            return null;

        int size = entries.size();
        if (size == 0)
            return null;

        if (cache != null)
        {
            Entry<P> entry = cache.get();
            if (entry != null && ((ConcurrentEntry<P>)entry).tryAcquire())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("returning cached entry {} for {}", entry, this);
                return entry;
            }
        }

        int index = startIndex(size);

        for (int tries = size; tries-- > 0; )
        {
            try
            {
                ConcurrentEntry<P> entry = (ConcurrentEntry<P>)entries.get(index);
                if (entry != null && entry.tryAcquire())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("returning entry {} for {}", entry, this);
                    return entry;
                }
            }
            catch (IndexOutOfBoundsException e)
            {
                LOG.trace("IGNORED", e);
                size = entries.size();
                // Size can be 0 when the pool is in the middle of
                // acquiring a connection while another thread
                // removes the last one from the pool.
                if (size == 0)
                    break;
            }
            if (++index == size)
                index = 0;
        }

        return null;
    }

    private int startIndex(int size)
    {
        return switch (strategyType)
        {
            case FIRST -> 0;
            case RANDOM -> ThreadLocalRandom.current().nextInt(size);
            case ROUND_ROBIN -> nextIndex.getAndUpdate(c -> Math.max(0, c + 1)) % size;
            case THREAD_ID -> (int)(Thread.currentThread().getId() % size);
        };
    }

    private boolean release(Entry<P> entry)
    {
        boolean released = ((ConcurrentEntry<P>)entry).tryRelease();
        if (released && cache != null)
            cache.set(entry);
        if (LOG.isDebugEnabled())
            LOG.debug("released {} {} for {}", released, entry, this);
        return released;
    }

    private boolean remove(Entry<P> entry)
    {
        boolean removed = ((ConcurrentEntry<P>)entry).tryRemove();
        if (cache != null)
            cache.set(null);
        if (LOG.isDebugEnabled())
            LOG.debug("removed {} {} for {}", removed, entry, this);
        if (!removed)
            return false;

        // No need to lock, no race with reserve()
        // and the race with terminate() is harmless.
        boolean evicted = entries.remove(entry);
        if (LOG.isDebugEnabled())
            LOG.debug("evicted {} {} for {}", evicted, entry, this);

        return true;
    }

    @Override
    public boolean isTerminated()
    {
        return terminated;
    }

    @Override
    public Collection<Entry<P>> terminate()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("terminating {}", this);

        List<Entry<P>> copy;
        try (AutoLock ignored = lock.lock())
        {
            // Field this.terminated must be modified with the lock held
            // because the list of entries is modified, see reserve().
            terminated = true;
            copy = List.copyOf(entries);
            entries.clear();
        }

        // Iterate over the copy and terminate its entries.
        copy.forEach(entry -> ((ConcurrentEntry<P>)entry).terminate());
        return copy;
    }

    private boolean terminate(Entry<P> entry)
    {
        boolean terminated = ((ConcurrentEntry<P>)entry).tryTerminate();
        if (cache != null)
            cache.set(null);
        if (!terminated)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("entry still in use or already terminated {} for {}", entry, this);
        }
        return terminated;
    }

    @Override
    public int size()
    {
        return entries.size();
    }

    @Override
    public int getMaxSize()
    {
        return maxSize;
    }

    @Override
    public Stream<Entry<P>> stream()
    {
        return entries.stream();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this,
            new DumpableCollection("entries", entries));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[inUse=%d,size=%d,max=%d,terminated=%b]",
            getClass().getSimpleName(),
            hashCode(),
            getInUseCount(),
            size(),
            getMaxSize(),
            isTerminated());
    }

    /**
     * The type of the strategy to use for the pool.
     * The strategy primarily determines where iteration over the pool entries begins.
     */
    public enum StrategyType
    {
        /**
         * A strategy that looks for an entry always starting from the first entry.
         * It will favour the early entries in the pool, but may contend on them more.
         */
        FIRST,

        /**
         * A strategy that looks for an entry by iterating from a random starting
         * index.  No entries are favoured and contention is reduced.
         */
        RANDOM,

        /**
         * A strategy that uses the {@link Thread#getId()} of the current thread
         * to select a starting point for an entry search.  Whilst not as performant as
         * using the {@link ThreadLocal} cache, it may be suitable when the pool is
         * substantially smaller than the number of available threads.
         * No entries are favoured and contention is reduced.
         */
        THREAD_ID,

        /**
         * A strategy that looks for an entry by iterating from a starting point
         * that is incremented on every search. This gives similar results to the
         * random strategy but with more predictable behaviour.
         * No entries are favoured and contention is reduced.
         */
        ROUND_ROBIN
    }

    /**
     * <p>A Pool entry that holds metadata and a pooled object.</p>
     */
    public static class ConcurrentEntry<E> implements Entry<E>
    {
        // HI:
        //   -2 -> already removed
        //   -1 -> terminated | removed
        //    0 -> available
        // LO:
        //   -1  -> reserved
        //    0  -> idle
        //    1+ -> multiplex count
        private final AtomicBiInteger state = new AtomicBiInteger(0, -1);
        private final ConcurrentPool<E> pool;
        // The pooled object. This is not volatile as it is set once and then never changed.
        // Other threads accessing must check the state field above first, so a good before/after
        // relationship exists to make a memory barrier.
        private E pooled;

        public ConcurrentEntry(ConcurrentPool<E> pool)
        {
            this.pool = pool;
        }

        @Override
        public boolean enable(E pooled, boolean acquire)
        {
            Objects.requireNonNull(pooled);

            if (!isReserved())
            {
                if (isTerminated())
                    return false;
                throw new IllegalStateException("Entry already enabled " + this + " for " + pool);
            }
            this.pooled = pooled;

            if (tryEnable(acquire))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("enabled {} for {}", this, pool);
                return true;
            }

            this.pooled = null;
            if (isTerminated())
                return false;
            throw new IllegalStateException("Entry already enabled " + this + " for " + pool);
        }

        @Override
        public E getPooled()
        {
            return pooled;
        }

        @Override
        public boolean release()
        {
            return pool.release(this);
        }

        @Override
        public boolean remove()
        {
            return pool.remove(this);
        }

        private boolean terminate()
        {
            return pool.terminate(this);
        }

        /**
         * <p>Tries to enable, and possible also acquire, this {@code Entry}.</p>
         *
         * @param acquire whether to also acquire this {@code Entry}
         * @return whether this {@code Entry} was enabled
         */
        private boolean tryEnable(boolean acquire)
        {
            return state.compareAndSet(0, 0, -1, acquire ? 1 : 0);
        }

        /**
         * <p>Tries to acquire this {@code Entry}.</p>
         *
         * @return whether this {@code Entry} was acquired
         */
        private boolean tryAcquire()
        {
            while (true)
            {
                long encoded = state.get();
                // Already terminated?
                if (AtomicBiInteger.getHi(encoded) < 0)
                    return false;

                int multiplexCount = AtomicBiInteger.getLo(encoded);
                // Not yet enabled?
                if (multiplexCount < 0)
                    return false;

                int maxMultiplexed = pool.getMaxMultiplex(pooled);
                if (maxMultiplexed > 0 && multiplexCount >= maxMultiplexed)
                    return false;

                int newMultiplexCount = multiplexCount + 1;
                // Handles integer overflow.
                if (newMultiplexCount < 0)
                    return false;

                if (state.compareAndSet(encoded, 0, newMultiplexCount))
                    return true;
            }
        }

        /**
         * <p>Tries to release this {@code Entry}.</p>
         *
         * @return true if this {@code Entry} was released,
         * false if {@link #tryRemove()} should be called.
         */
        private boolean tryRelease()
        {
            while (true)
            {
                long encoded = state.get();
                if (AtomicBiInteger.getHi(encoded) < 0)
                    return false;

                int multiplexCount = AtomicBiInteger.getLo(encoded);
                if (multiplexCount <= 0)
                    return false;
                int newMultiplexCount = multiplexCount - 1;
                if (state.compareAndSet(encoded, 0, newMultiplexCount))
                    return true;
            }
        }

        /**
         * <p>Tries to remove this {@code Entry} by marking it as terminated.</p>
         *
         * @return whether this {@code Entry} can be removed
         */
        private boolean tryRemove()
        {
            while (true)
            {
                long encoded = state.get();
                int removed = AtomicBiInteger.getHi(encoded);
                int multiplexCount = AtomicBiInteger.getLo(encoded);

                // The entry was already removed once before.
                if (removed == -2)
                    return false;

                int newMultiplexCount;
                if (multiplexCount <= 0)
                {
                    // Removing a reserved or idle entry;
                    // keep the same multiplex count.
                    newMultiplexCount = multiplexCount;
                }
                else
                {
                    // Removing an in-use entry;
                    // decrement the multiplex count.
                    newMultiplexCount = multiplexCount - 1;
                }

                // Always mark the entry as removed,
                // and update the multiplex count.
                boolean result = newMultiplexCount <= 0;
                removed = result ? -2 : -1;
                if (state.compareAndSet(encoded, removed, newMultiplexCount))
                    return result;
            }
        }

        private boolean tryTerminate()
        {
            while (true)
            {
                long encoded = state.get();
                if (AtomicBiInteger.getHi(encoded) < 0)
                    return false;
                int multiplexCount = AtomicBiInteger.getLo(encoded);
                if (state.compareAndSet(encoded, -1, multiplexCount))
                    return multiplexCount <= 0;
            }
        }

        @Override
        public boolean isTerminated()
        {
            return state.getHi() < 0;
        }

        @Override
        public boolean isReserved()
        {
            return state.getLo() < 0;
        }

        @Override
        public boolean isIdle()
        {
            return state.getLo() == 0;
        }

        @Override
        public boolean isInUse()
        {
            return state.getLo() > 0;
        }

        @Override
        public String toString()
        {
            long encoded = state.get();
            return String.format("%s@%x{terminated=%b,multiplex=%d,pooled=%s}",
                getClass().getSimpleName(),
                hashCode(),
                AtomicBiInteger.getHi(encoded) < 0,
                AtomicBiInteger.getLo(encoded),
                getPooled());
        }
    }
}
