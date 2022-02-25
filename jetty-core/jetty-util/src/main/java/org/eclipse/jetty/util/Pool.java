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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A pool of objects, with optional support for multiplexing,
 * max usage count and several optimized strategies plus
 * an optional {@link ThreadLocal} cache of the last release entry.</p>
 * <p>When the method {@link #close()} is called, all {@link Closeable}s
 * object pooled by the pool are also closed.</p>
 *
 * @param <T> the type of the pooled objects
 */
@ManagedObject
public class Pool<T> implements AutoCloseable, Dumpable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(Pool.class);

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final int maxEntries;
    private final StrategyType strategyType;
    /*
     * The cache is used to avoid hammering on the first index of the entry list.
     * Caches can become poisoned (i.e.: containing entries that are in use) when
     * the release isn't done by the acquiring thread or when the entry pool is
     * undersized compared to the load applied on it.
     * When an entry can't be found in the cache, the global list is iterated
     * with the configured strategy so the cache has no visible effect besides performance.
     */
    private final AutoLock lock = new AutoLock();
    private final ThreadLocal<Entry> cache;
    private final AtomicInteger nextIndex;
    private volatile boolean closed;
    @Deprecated
    private volatile int maxUsage = -1;
    @Deprecated
    private volatile int maxMultiplex = -1;

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
         * using the {@link ThreadLocal} cache, it may be suitable when the pool is substantially smaller
         * than the number of available threads.
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
     * Construct a Pool with a specified lookup strategy and no
     * {@link ThreadLocal} cache.
     *
     * @param strategyType The strategy to used for looking up entries.
     * @param maxEntries the maximum amount of entries that the pool will accept.
     */
    public Pool(StrategyType strategyType, int maxEntries)
    {
        this(strategyType, maxEntries, false);
    }

    /**
     * Construct a Pool with the specified thread-local cache size and
     * an optional {@link ThreadLocal} cache.
     *
     * @param strategyType The strategy to used for looking up entries.
     * @param maxEntries the maximum amount of entries that the pool will accept.
     * @param cache True if a {@link ThreadLocal} cache should be used to try the most recently released entry.
     */
    public Pool(StrategyType strategyType, int maxEntries, boolean cache)
    {
        this.maxEntries = maxEntries;
        this.strategyType = strategyType;
        this.cache = cache ? new ThreadLocal<>() : null;
        this.nextIndex = strategyType == StrategyType.ROUND_ROBIN ? new AtomicInteger() : null;
    }

    /**
     * @return the number of reserved entries
     */
    @ManagedAttribute("The number of reserved entries")
    public int getReservedCount()
    {
        return (int)entries.stream().filter(Entry::isReserved).count();
    }

    /**
     * @return the number of idle entries
     */
    @ManagedAttribute("The number of idle entries")
    public int getIdleCount()
    {
        return (int)entries.stream().filter(Entry::isIdle).count();
    }

    /**
     * @return the number of in-use entries
     */
    @ManagedAttribute("The number of in-use entries")
    public int getInUseCount()
    {
        return (int)entries.stream().filter(Entry::isInUse).count();
    }

    /**
     * @return the number of closed entries
     */
    @ManagedAttribute("The number of closed entries")
    public int getClosedCount()
    {
        return (int)entries.stream().filter(Entry::isClosed).count();
    }

    /**
     * @return the maximum number of entries
     */
    @ManagedAttribute("The maximum number of entries")
    public int getMaxEntries()
    {
        return maxEntries;
    }

    /**
     * @return the default maximum multiplex count of entries
     * @deprecated Multiplex functionalities will be removed
     */
    @ManagedAttribute("The default maximum multiplex count of entries")
    @Deprecated
    public int getMaxMultiplex()
    {
        return maxMultiplex == -1 ? 1 : maxMultiplex;
    }

    /**
     * <p>Retrieves the max multiplex count for the given pooled object.</p>
     *
     * @param pooled the pooled object
     * @return the max multiplex count for the given pooled object
     * @deprecated Multiplex functionalities will be removed
     */
    @Deprecated
    protected int getMaxMultiplex(T pooled)
    {
        return getMaxMultiplex();
    }

    /**
     * <p>Sets the default maximum multiplex count for the Pool's entries.</p>
     *
     * @param maxMultiplex the default maximum multiplex count of entries
     * @deprecated Multiplex functionalities will be removed
     */
    @Deprecated
    public final void setMaxMultiplex(int maxMultiplex)
    {
        if (maxMultiplex < 1)
            throw new IllegalArgumentException("Max multiplex must be >= 1");
        try (AutoLock l = lock.lock())
        {
            if (closed)
                return;

            if (entries.stream().anyMatch(MonoEntry.class::isInstance))
                throw new IllegalStateException("Pool entries do not support multiplexing");

            this.maxMultiplex = maxMultiplex;
        }
    }

    /**
     * <p>Returns the maximum number of times the entries of the pool
     * can be acquired.</p>
     *
     * @return the default maximum usage count of entries
     * @deprecated MaxUsage functionalities will be removed
     */
    @ManagedAttribute("The default maximum usage count of entries")
    @Deprecated
    public int getMaxUsageCount()
    {
        return maxUsage;
    }

    /**
     * <p>Retrieves the max usage count for the given pooled object.</p>
     *
     * @param pooled the pooled object
     * @return the max usage count for the given pooled object
     * @deprecated MaxUsage functionalities will be removed
     */
    @Deprecated
    protected int getMaxUsageCount(T pooled)
    {
        return getMaxUsageCount();
    }

    /**
     * <p>Sets the maximum usage count for the Pool's entries.</p>
     * <p>All existing idle entries that have a usage count larger
     * than this new value are removed from the Pool and closed.</p>
     *
     * @param maxUsageCount the default maximum usage count of entries
     * @deprecated MaxUsage functionalities will be removed
     */
    @Deprecated
    public final void setMaxUsageCount(int maxUsageCount)
    {
        if (maxUsageCount == 0)
            throw new IllegalArgumentException("Max usage count must be != 0");

        // Iterate the entries, remove overused ones and collect a list of the closeable removed ones.
        List<Closeable> copy;
        try (AutoLock l = lock.lock())
        {
            if (closed)
                return;

            if (entries.stream().anyMatch(MonoEntry.class::isInstance))
                throw new IllegalStateException("Pool entries do not support max usage");

            this.maxUsage = maxUsageCount;

            copy = entries.stream()
                .filter(entry -> entry.isIdleAndOverUsed() && remove(entry) && entry.pooled instanceof Closeable)
                .map(entry -> (Closeable)entry.pooled)
                .collect(Collectors.toList());
        }

        // Iterate the copy and close the collected entries.
        copy.forEach(IO::close);
    }

    /**
     * <p>Creates a new disabled slot into the pool.</p>
     * <p>The returned entry must ultimately have the {@link Entry#enable(Object, boolean)}
     * method called or be removed via {@link Pool.Entry#remove()} or
     * {@link Pool#remove(Pool.Entry)}.</p>
     *
     * @param allotment the desired allotment, where each entry handles an allotment of maxMultiplex,
     * or a negative number to always trigger the reservation of a new entry.
     * @return a disabled entry that is contained in the pool,
     * or null if the pool is closed or if the pool already contains
     * {@link #getMaxEntries()} entries, or the allotment has already been reserved
     * @deprecated Use {@link #reserve()} instead
     */
    @Deprecated
    public Entry reserve(int allotment)
    {
        try (AutoLock l = lock.lock())
        {
            if (closed)
                return null;

            int space = maxEntries - entries.size();
            if (space <= 0)
                return null;

            if (allotment >= 0 && (getReservedCount() * getMaxMultiplex()) >= allotment)
                return null;

            Entry entry = newEntry();
            entries.add(entry);
            return entry;
        }
    }

    /**
     * <p>Creates a new disabled slot into the pool.</p>
     * <p>The returned entry must ultimately have the {@link Entry#enable(Object, boolean)}
     * method called or be removed via {@link Pool.Entry#remove()} or
     * {@link Pool#remove(Pool.Entry)}.</p>
     *
     * @return a disabled entry that is contained in the pool,
     * or null if the pool is closed or if the pool already contains
     * {@link #getMaxEntries()} entries
     */
    public Entry reserve()
    {
        try (AutoLock l = lock.lock())
        {
            if (closed)
                return null;

            // If we have no space
            if (entries.size() >= maxEntries)
                return null;

            Entry entry = newEntry();
            entries.add(entry);
            return entry;
        }
    }

    private Entry newEntry()
    {
        // Do not allow more than 2 implementations of Entry, otherwise call sites in Pool
        // referencing Entry methods will become mega-morphic and kill the performance.
        if (maxMultiplex >= 0 || maxUsage >= 0)
            return new MultiEntry();
        return new MonoEntry();
    }

    /**
     * <p>Acquires an entry from the pool.</p>
     * <p>Only enabled entries will be returned from this method
     * and their {@link Entry#enable(Object, boolean)}
     * method must not be called.</p>
     *
     * @return an entry from the pool or null if none is available.
     */
    public Entry acquire()
    {
        if (closed)
            return null;

        int size = entries.size();
        if (size == 0)
            return null;

        if (cache != null)
        {
            Pool<T>.Entry entry = cache.get();
            if (entry != null && entry.tryAcquire())
                return entry;
        }

        int index = startIndex(size);

        for (int tries = size; tries-- > 0;)
        {
            try
            {
                Pool<T>.Entry entry = entries.get(index);
                if (entry != null && entry.tryAcquire())
                    return entry;
            }
            catch (IndexOutOfBoundsException e)
            {
                LOGGER.trace("IGNORED", e);
                size = entries.size();
                // Size can be 0 when the pool is in the middle of
                // acquiring a connection while another thread
                // removes the last one from the pool.
                if (size == 0)
                    break;
            }
            index = (index + 1) % size;
        }
        return null;
    }

    private int startIndex(int size)
    {
        switch (strategyType)
        {
            case FIRST:
                return 0;
            case RANDOM:
                return ThreadLocalRandom.current().nextInt(size);
            case ROUND_ROBIN:
                return nextIndex.getAndUpdate(c -> Math.max(0, c + 1)) % size;
            case THREAD_ID:
                return (int)(Thread.currentThread().getId() % size);
            default:
                throw new IllegalArgumentException("Unknown strategy type: " + strategyType);
        }
    }

    /**
     * <p>Acquires an entry from the pool,
     * reserving and creating a new entry if necessary.</p>
     *
     * @param creator a function to create the pooled value for a reserved entry.
     * @return an entry from the pool or null if none is available.
     */
    public Entry acquire(Function<Pool<T>.Entry, T> creator)
    {
        Entry entry = acquire();
        if (entry != null)
            return entry;

        entry = reserve();
        if (entry == null)
            return null;

        T value;
        try
        {
            value = creator.apply(entry);
        }
        catch (Throwable th)
        {
            remove(entry);
            throw th;
        }

        if (value == null)
        {
            remove(entry);
            return null;
        }

        return entry.enable(value, true) ? entry : null;
    }

    /**
     * <p>Releases an {@link #acquire() acquired} entry to the pool.</p>
     * <p>Entries that are acquired from the pool but never released
     * will result in a memory leak.</p>
     *
     * @param entry the value to return to the pool
     * @return true if the entry was released and could be acquired again,
     * false if the entry should be removed by calling {@link #remove(Pool.Entry)}
     * and the object contained by the entry should be disposed.
     */
    public boolean release(Entry entry)
    {
        if (closed)
            return false;

        boolean released = entry.tryRelease();
        if (released && cache != null)
            cache.set(entry);
        return released;
    }

    /**
     * <p>Removes an entry from the pool.</p>
     *
     * @param entry the value to remove
     * @return true if the entry was removed, false otherwise
     */
    public boolean remove(Entry entry)
    {
        if (closed)
            return false;

        if (!entry.tryRemove())
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Attempt to remove an object from the pool that is still in use: {}", entry);
            return false;
        }

        boolean removed = entries.remove(entry);
        if (!removed && LOGGER.isDebugEnabled())
            LOGGER.debug("Attempt to remove an object from the pool that does not exist: {}", entry);

        return removed;
    }

    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public void close()
    {
        List<Entry> copy;
        try (AutoLock l = lock.lock())
        {
            closed = true;
            copy = new ArrayList<>(entries);
            entries.clear();
        }

        // iterate the copy and close its entries
        for (Entry entry : copy)
        {
            boolean removed = entry.tryRemove();
            if (removed)
            {
                if (entry.pooled instanceof Closeable)
                    IO.close((Closeable)entry.pooled);
            }
            else
            {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Pooled object still in use: {}", entry);
            }
        }
    }

    public int size()
    {
        return entries.size();
    }

    public Collection<Entry> values()
    {
        return Collections.unmodifiableCollection(entries);
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
        return String.format("%s@%x[inUse=%d,size=%d,max=%d,closed=%b]",
            getClass().getSimpleName(),
            hashCode(),
            getInUseCount(),
            size(),
            getMaxEntries(),
            isClosed());
    }

    /**
     * <p>A Pool entry that holds metadata and a pooled object.</p>
     */
    public abstract class Entry
    {
        // The pooled object.  This is not volatile as it is set once and then never changed.
        // Other threads accessing must check the state field above first, so a good before/after
        // relationship exists to make a memory barrier.
        private T pooled;

        /**
         * <p>Enables this, previously {@link #reserve() reserved}, Entry.</p>
         * <p>An entry returned from the {@link #reserve()} method must be enabled with this method,
         * once and only once, before it is usable by the pool.</p>
         * <p>The entry may be enabled and not acquired, in which case it is immediately available to be
         * acquired, potentially by another thread; or it can be enabled and acquired atomically so that
         * no other thread can acquire it, although the acquire may still fail if the pool has been closed.</p>
         *
         * @param pooled the pooled object for this Entry
         * @param acquire whether this Entry should be atomically enabled and acquired
         * @return whether this Entry was enabled
         * @throws IllegalStateException if this Entry was already enabled
         */
        public boolean enable(T pooled, boolean acquire)
        {
            Objects.requireNonNull(pooled);

            if (!isReserved())
            {
                if (isClosed())
                    return false; // Pool has been closed
                throw new IllegalStateException("Entry already enabled: " + this);
            }
            this.pooled = pooled;

            if (tryEnable(acquire))
                return true;

            this.pooled = null;
            if (isClosed())
                return false; // Pool has been closed
            throw new IllegalStateException("Entry already enabled: " + this);
        }

        /**
         * @return the pooled object
         */
        public T getPooled()
        {
            return pooled;
        }

        /**
         * <p>Releases this Entry.</p>
         * <p>This is equivalent to calling {@link Pool#release(Pool.Entry)} passing this entry.</p>
         *
         * @return whether this Entry was released
         */
        public boolean release()
        {
            return Pool.this.release(this);
        }

        /**
         * <p>Removes this Entry from the Pool.</p>
         * <p>This is equivalent to calling {@link Pool#remove(Pool.Entry)} passing this entry.</p>
         *
         * @return whether this Entry was removed
         */
        public boolean remove()
        {
            return Pool.this.remove(this);
        }

        /**
         * <p>Tries to enable, and possible also acquire, this Entry.</p>
         *
         * @param acquire whether to also acquire this Entry
         * @return whether this Entry was enabled
         */
        abstract boolean tryEnable(boolean acquire);

        /**
         * <p>Tries to acquire this Entry.</p>
         *
         * @return whether this Entry was acquired
         */
        abstract boolean tryAcquire();

        /**
         * <p>Tries to release this Entry.</p>
         *
         * @return true if this Entry was released,
         * false if {@link #tryRemove()} should be called.
         */
        abstract boolean tryRelease();

        /**
         * <p>Tries to remove the entry by marking it as closed.</p>
         *
         * @return whether the entry can be removed from the containing pool
         */
        abstract boolean tryRemove();

        /**
         * @return whether this Entry is closed
         */
        public abstract boolean isClosed();

        /**
         * @return whether this Entry is reserved
         */
        public abstract boolean isReserved();

        /**
         * @return whether this Entry is idle
         */
        public abstract boolean isIdle();

        /**
         * @return whether this entry is in use.
         */
        public abstract boolean isInUse();

        /**
         * @return whether this entry has been used beyond {@link #getMaxUsageCount()}
         * @deprecated MaxUsage functionalities will be removed
         */
        @Deprecated
        public boolean isOverUsed()
        {
            return false;
        }

        boolean isIdleAndOverUsed()
        {
            return false;
        }

        // Only for testing.
        int getUsageCount()
        {
            return 0;
        }

        // Only for testing.
        void setUsageCount(int usageCount)
        {
        }
    }

    /**
     * <p>A Pool entry that holds metadata and a pooled object,
     * that can only be acquired concurrently at most once, and
     * can be acquired/released multiple times.</p>
     */
    private class MonoEntry extends Entry
    {
        // MIN_VALUE => pending; -1 => closed; 0 => idle; 1 => active;
        private final AtomicInteger state = new AtomicInteger(Integer.MIN_VALUE);

        @Override
        protected boolean tryEnable(boolean acquire)
        {
            return state.compareAndSet(Integer.MIN_VALUE, acquire ? 1 : 0);
        }

        @Override
        boolean tryAcquire()
        {
            while (true)
            {
                int s = state.get();
                if (s != 0)
                    return false;
                if (state.compareAndSet(s, 1))
                    return true;
            }
        }

        @Override
        boolean tryRelease()
        {
            while (true)
            {
                int s = state.get();
                if (s < 0)
                    return false;
                if (s == 0)
                    throw new IllegalStateException("Cannot release an already released entry");
                if (state.compareAndSet(s, 0))
                    return true;
            }
        }

        @Override
        boolean tryRemove()
        {
            state.set(-1);
            return true;
        }

        @Override
        public boolean isClosed()
        {
            return state.get() < 0;
        }

        @Override
        public boolean isReserved()
        {
            return state.get() == Integer.MIN_VALUE;
        }

        @Override
        public boolean isIdle()
        {
            return state.get() == 0;
        }

        @Override
        public boolean isInUse()
        {
            return state.get() == 1;
        }

        @Override
        public String toString()
        {
            String s;
            switch (state.get())
            {
                case Integer.MIN_VALUE:
                    s = "PENDING";
                    break;
                case -1:
                    s = "CLOSED";
                    break;
                case 0:
                    s = "IDLE";
                    break;
                default:
                    s = "ACTIVE";
            }
            return String.format("%s@%x{%s,pooled=%s}",
                getClass().getSimpleName(),
                hashCode(),
                s,
                getPooled());
        }
    }

    /**
     * <p>A Pool entry that holds metadata and a pooled object,
     * that can be acquired concurrently multiple times, and
     * can be acquired/released multiple times.</p>
     */
    class MultiEntry extends Entry
    {
        // hi: MIN_VALUE => pending; -1 => closed; 0+ => usage counter;
        // lo: 0 => idle; positive => multiplex counter
        private final AtomicBiInteger state;

        MultiEntry()
        {
            this.state = new AtomicBiInteger(Integer.MIN_VALUE, 0);
        }

        @Override
        void setUsageCount(int usageCount)
        {
            this.state.getAndSetHi(usageCount);
        }

        @Override
        protected boolean tryEnable(boolean acquire)
        {
            int usage = acquire ? 1 : 0;
            return state.compareAndSet(Integer.MIN_VALUE, usage, 0, usage);
        }

        /**
         * <p>Tries to acquire the entry if possible by incrementing both the usage
         * count and the multiplex count.</p>
         *
         * @return true if the usage count is less than {@link #getMaxUsageCount()} and
         * the multiplex count is less than {@link #getMaxMultiplex(Object)} and
         * the entry is not closed, false otherwise.
         */
        @Override
        boolean tryAcquire()
        {
            while (true)
            {
                long encoded = state.get();
                int usageCount = AtomicBiInteger.getHi(encoded);
                int multiplexCount = AtomicBiInteger.getLo(encoded);
                boolean closed = usageCount < 0;
                if (closed)
                    return false;
                T pooled = getPooled();
                int maxUsageCount = getMaxUsageCount(pooled);
                if (maxUsageCount > 0 && usageCount >= maxUsageCount)
                    return false;
                int maxMultiplexed = getMaxMultiplex(pooled);
                if (maxMultiplexed > 0 && multiplexCount >= maxMultiplexed)
                    return false;

                // Prevent overflowing the usage counter by capping it at Integer.MAX_VALUE.
                int newUsageCount = usageCount == Integer.MAX_VALUE ? Integer.MAX_VALUE : usageCount + 1;
                if (state.compareAndSet(encoded, newUsageCount, multiplexCount + 1))
                    return true;
            }
        }

        /**
         * <p>Tries to release the entry if possible by decrementing the multiplex
         * count unless the entity is closed.</p>
         *
         * @return true if the entry was released,
         * false if {@link #tryRemove()} should be called.
         */
        @Override
        boolean tryRelease()
        {
            int newMultiplexCount;
            int usageCount;
            while (true)
            {
                long encoded = state.get();
                usageCount = AtomicBiInteger.getHi(encoded);
                boolean closed = usageCount < 0;
                if (closed)
                    return false;

                newMultiplexCount = AtomicBiInteger.getLo(encoded) - 1;
                if (newMultiplexCount < 0)
                    throw new IllegalStateException("Cannot release an already released entry");

                if (state.compareAndSet(encoded, usageCount, newMultiplexCount))
                    break;
            }

            int currentMaxUsageCount = getMaxUsageCount(getPooled());
            boolean overUsed = currentMaxUsageCount > 0 && usageCount >= currentMaxUsageCount;
            return !(overUsed && newMultiplexCount == 0);
        }

        /**
         * <p>Tries to remove the entry by marking it as closed and decrementing the multiplex counter.</p>
         * <p>The multiplex counter will never go below zero and if it reaches zero, the entry is considered removed.</p>
         *
         * @return true if the entry can be removed from the containing pool, false otherwise.
         */
        @Override
        boolean tryRemove()
        {
            while (true)
            {
                long encoded = state.get();
                int usageCount = AtomicBiInteger.getHi(encoded);
                int multiplexCount = AtomicBiInteger.getLo(encoded);
                int newMultiplexCount = Math.max(multiplexCount - 1, 0);

                boolean removed = state.compareAndSet(usageCount, -1, multiplexCount, newMultiplexCount);
                if (removed)
                    return newMultiplexCount == 0;
            }
        }

        @Override
        public boolean isClosed()
        {
            return state.getHi() < 0;
        }

        @Override
        public boolean isReserved()
        {
            return state.getHi() == Integer.MIN_VALUE;
        }

        @Override
        public boolean isIdle()
        {
            long encoded = state.get();
            return AtomicBiInteger.getHi(encoded) >= 0 && AtomicBiInteger.getLo(encoded) == 0;
        }

        @Override
        public boolean isInUse()
        {
            long encoded = state.get();
            return AtomicBiInteger.getHi(encoded) >= 0 && AtomicBiInteger.getLo(encoded) > 0;
        }

        @Override
        public boolean isOverUsed()
        {
            int maxUsageCount = getMaxUsageCount();
            int usageCount = state.getHi();
            return maxUsageCount > 0 && usageCount >= maxUsageCount;
        }

        @Override
        boolean isIdleAndOverUsed()
        {
            int maxUsageCount = getMaxUsageCount();
            long encoded = state.get();
            int usageCount = AtomicBiInteger.getHi(encoded);
            int multiplexCount = AtomicBiInteger.getLo(encoded);
            return maxUsageCount > 0 && usageCount >= maxUsageCount && multiplexCount == 0;
        }

        @Override
        int getUsageCount()
        {
            return Math.max(state.getHi(), 0);
        }

        @Override
        public String toString()
        {
            long encoded = state.get();
            int usageCount = AtomicBiInteger.getHi(encoded);
            int multiplexCount = AtomicBiInteger.getLo(encoded);

            String state = usageCount < 0
                ? (usageCount == Integer.MIN_VALUE ? "PENDING" : "CLOSED")
                : (multiplexCount == 0 ? "IDLE" : "ACTIVE");

            return String.format("%s@%x{%s,usage=%d,multiplex=%d,pooled=%s}",
                getClass().getSimpleName(),
                hashCode(),
                state,
                Math.max(usageCount, 0),
                Math.max(multiplexCount, 0),
                getPooled());
        }
    }
}
