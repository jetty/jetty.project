//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Locker;

/**
 * A fast pool of objects, with optional support for
 * multiplexing, max usage count and optimal strategies such as thread-local caching.
 * <p>
 * When acquiring an entry in the pool, this class will call a
 * strategy passed or created in the constructor.  The available
 * strategies are:
 * <dl>
 *     <dt>{@link SearchStrategy}</dt>
 *     <dd>This strategy iterates over all entries trying to acquire.
 *     </dd>
 *     <dt>{@link CompositeStrategy}</dt>
 *     <dd>This strategy combines two other strategies and is typically
 *     used to combine a strategy like {@link ThreadLocalStrategy} with
 *     {@link SearchStrategy}.
 *     </dd>
 *     <dt>{@link RoundRobinStrategy}</dt>
 *     <dd>Entries are tried in sequence so that all entries in the pool are
 *     used one after the other. If the next entry cannot be acquired, entries
 *     in the next slot are tried.
 *     <dt>{@link ThreadLocalStrategy}</dt>
 *     <dd>This strategy is a threadlocal strategy that remembers
 *     a single entry previously used by the current thread.
 *     </dd>
 *     <dt>{@link ThreadLocalListStrategy}</dt>
 *     <dd>This strategy is a threadlocal caching mechanism that remembers
 *     up to N entries previously used by the current thread.
 *     </dd>
 *     <dt>{@link RandomStrategy}</dt>
 *     <dd>A random entry is tried</dd>
 *     <dt>{@link Pool.LeastRecentlyUsedStrategy}</dt>
 *     <dd>The least recently used entries are tried until one can be acquired</dd>
 * </dl>
 * <p>
 * When the method {@link #close()} is called, all {@link Closeable}s in the pool
 * are also closed.
 * </p>
 * @param <T>
 */
public class Pool<T> implements AutoCloseable, Dumpable
{
    private static final Logger LOGGER = Log.getLogger(Pool.class);

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    /*
     * The cache is used to avoid hammering on the first index of the entry list.
     * Caches can become poisoned (i.e.: containing entries that are in use) when
     * the release isn't done by the acquiring thread or when the entry pool is
     * undersized compared to the load applied on it.
     * When an entry can't be found in the cache, the global list is iterated
     * normally so the cache has no visible effect besides performance.
     */

    private final Strategy<T> strategy;
    private final Locker locker = new Locker();
    private final int maxEntries;
    private final AtomicInteger pending = new AtomicInteger();
    private volatile boolean closed;
    private volatile int maxMultiplex = 1;
    private volatile int maxUsageCount = -1;

    /**
     * Construct a Pool with the specified thread-local cache size.
     *
     * @param maxEntries the maximum amount of entries that the pool will accept.
     * @param cacheSize the thread-local cache size. A value of 1 will use the
     *                  {@link ThreadLocalStrategy} and {@link SearchStrategy},
     *                  a value greater than 1 will use a {@link ThreadLocalListStrategy}
     *                  and {@link SearchStrategy},
     *                  otherwise just a {@link SearchStrategy} will be used.
     */
    public Pool(int maxEntries, int cacheSize)
    {
        this(maxEntries, cacheSize < 0 ? null : new CompositeStrategy<>(
            cacheSize == 1 ? new ThreadLocalStrategy<>() : new ThreadLocalListStrategy<>(cacheSize),
            new SearchStrategy<>()));
    }

    /**
     * @param maxEntries the maximum amount of entries that the pool will accept.
     * @param strategy A strategy to use to optimise acquires. Only if the strategy fails to acquire an entry will the
     *                 pool do a brute force iteration over the pool.
     */
    public Pool(int maxEntries, Strategy<T> strategy)
    {
        this.maxEntries = maxEntries;
        this.strategy = strategy == null ? new SearchStrategy<>() : strategy;
    }

    public int getReservedCount()
    {
        return pending.get();
    }

    public int getIdleCount()
    {
        return (int)entries.stream().filter(Entry::isIdle).count();
    }

    public int getInUseCount()
    {
        return (int)entries.stream().filter(Entry::isInUse).count();
    }

    public int getMaxEntries()
    {
        return maxEntries;
    }

    public int getMaxMultiplex()
    {
        return maxMultiplex;
    }

    public final void setMaxMultiplex(int maxMultiplex)
    {
        if (maxMultiplex < 1)
            throw new IllegalArgumentException("Max multiplex must be >= 1");
        this.maxMultiplex = maxMultiplex;
    }

    public int getMaxUsageCount()
    {
        return maxUsageCount;
    }

    public final void setMaxUsageCount(int maxUsageCount)
    {
        if (maxUsageCount == 0)
            throw new IllegalArgumentException("Max usage count must be != 0");
        this.maxUsageCount = maxUsageCount;
    }

    /**
     * Create a new disabled slot into the pool.
     * The returned entry must ultimately have the {@link Entry#enable(Object, boolean)}
     * method called or be removed via {@link Pool.Entry#remove()} or
     * {@link Pool#remove(Pool.Entry)}.
     *
     * @param allotment the desired allotment, where each entry handles an allotment of maxMultiplex,
     * or a negative number to always trigger the reservation of a new entry.
     * @return a disabled entry that is contained in the pool,
     * or null if the pool is closed or if the pool already contains
     * {@link #getMaxEntries()} entries, or the allotment has already been reserved
     */
    public Entry reserve(int allotment)
    {
        try (Locker.Lock l = locker.lock())
        {
            if (closed)
                return null;

            int space = maxEntries - entries.size();
            if (space <= 0)
                return null;

            // The pending count is an AtomicInteger that is only ever incremented here with
            // the lock held.  Thus the pending count can be reduced immediately after the
            // test below, but never incremented.  Thus the allotment limit can be enforced.
            if (allotment >= 0 && (pending.get() * getMaxMultiplex()) >= allotment)
                return null;
            pending.incrementAndGet();

            Entry entry = new Entry();
            strategy.reserve(entries, entry);
            return entry;
        }
    }

    /**
     * Acquire the entry from the pool at the specified index. This method bypasses the thread-local mechanism.
     *
     * @param idx the index of the entry to acquire.
     * @return the specified entry or null if there is none at the specified index or if it is not available.
     */
    @Deprecated
    public Entry acquireAt(int idx)
    {
        if (closed)
            return null;

        try
        {
            Entry entry = entries.get(idx);
            if (entry.tryAcquire())
                return entry;
        }
        catch (IndexOutOfBoundsException e)
        {
            // no entry at that index
        }
        return null;
    }

    /**
     * Acquire an entry from the pool.
     * Only enabled entries will be returned from this method and their enable method must not be called.
     * The implementation first tries the pool strategy and then a brute force iteration over entries.
     * @return an entry from the pool or null if none is available.
     */
    public Entry acquire()
    {
        if (closed)
            return null;
        return strategy.acquire(entries);
    }

    /**
     * Utility method to acquire an entry from the pool,
     * reserving and creating a new entry if necessary.
     *
     * @param creator a function to create the pooled value for a reserved entry.
     * @return an entry from the pool or null if none is available.
     */
    public Entry acquire(Function<Pool<T>.Entry, T> creator)
    {
        Entry entry = acquire();
        if (entry != null)
            return entry;

        entry = reserve(-1);
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
     * This method will return an acquired object to the pool. Objects
     * that are acquired from the pool but never released will result
     * in a memory leak.
     *
     * @param entry the value to return to the pool
     * @return true if the entry was released and could be acquired again,
     * false if the entry should be removed by calling {@link #remove(Pool.Entry)}
     * and the object contained by the entry should be disposed.
     * @throws NullPointerException if value is null
     */
    public boolean release(Entry entry)
    {
        if (closed)
            return false;

        return strategy.release(entries, entry);
    }

    /**
     * Remove a value from the pool.
     *
     * @param entry the value to remove
     * @return true if the entry was removed, false otherwise
     */
    public boolean remove(Entry entry)
    {
        if (closed)
            return false;

        return strategy.remove(entries, entry);
    }

    public boolean isClosed()
    {
        return closed;
    }

    @Override
    public void close()
    {
        List<Entry> copy;
        try (Locker.Lock l = locker.lock())
        {
            closed = true;
            copy = new ArrayList<>(entries);
            entries.clear();
        }

        // iterate the copy and close its entries
        for (Entry entry : copy)
        {
            if (entry.tryRemove() && entry.pooled instanceof Closeable)
                IO.close((Closeable)entry.pooled);
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
        Dumpable.dumpObjects(out, indent, this);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[size=%d closed=%s entries=%s]",
            getClass().getSimpleName(),
            hashCode(),
            entries.size(),
            closed,
            entries);
    }

    public class Entry
    {
        // hi: positive=open/maxUsage counter; negative=closed; MIN_VALUE pending
        // lo: multiplexing counter
        private final AtomicBiInteger state;

        // The pooled item.  This is not volatile as it is set once and then never changed.
        // Other threads accessing must check the state field above first, so a good before/after
        // relationship exists to make a memory barrier.
        private T pooled;

        Entry()
        {
            this.state = new AtomicBiInteger(Integer.MIN_VALUE, 0);
        }

        /** Enable a reserved entry {@link Entry}.
         * An entry returned from the {@link #reserve(int)} method must be enabled with this method,
         * once and only once, before it is usable by the pool.
         * The entry may be enabled and not acquired, in which case it is immediately available to be
         * acquired, potentially by another thread; or it can be enabled and acquired atomically so that
         * no other thread can acquire it, although the acquire may still fail if the pool has been closed.
         * @param pooled The pooled item for the entry
         * @param acquire If true the entry is atomically enabled and acquired.
         * @return true If the entry was enabled.
         * @throws IllegalStateException if the entry was already enabled
         */
        public boolean enable(T pooled, boolean acquire)
        {
            Objects.requireNonNull(pooled);

            if (state.getHi() != Integer.MIN_VALUE)
            {
                if (state.getHi() == -1)
                    return false; // Pool has been closed
                throw new IllegalStateException("Entry already enabled: " + this);
            }
            this.pooled = pooled;
            int usage = acquire ? 1 : 0;
            if (!state.compareAndSet(Integer.MIN_VALUE, usage, 0, usage))
            {
                this.pooled = null;
                if (state.getHi() == -1)
                    return false; // Pool has been closed
                throw new IllegalStateException("Entry already enabled: " + this);
            }
            pending.decrementAndGet();
            return true;
        }

        public T getPooled()
        {
            return pooled;
        }

        /**
         * Release the entry.
         * This is equivalent to calling {@link Pool#release(Pool.Entry)} passing this entry.
         * @return true if released.
         */
        public boolean release()
        {
            return Pool.this.release(this);
        }

        /**
         * Remove the entry.
         * This is equivalent to calling {@link Pool#remove(Pool.Entry)} passing this entry.
         * @return true if remove.
         */
        public boolean remove()
        {
            return Pool.this.remove(this);
        }

        /**
         * Try to acquire the entry if possible by incrementing both the usage
         * count and the multiplex count.
         * @return true if the usage count is &lt;= maxUsageCount and
         * the multiplex count is maxMultiplex and the entry is not closed,
         * false otherwise.
         */
        boolean tryAcquire()
        {
            while (true)
            {
                long encoded = state.get();
                int usageCount = AtomicBiInteger.getHi(encoded);
                boolean closed = usageCount < 0;
                int multiplexingCount = AtomicBiInteger.getLo(encoded);
                int currentMaxUsageCount = maxUsageCount;
                if (closed || multiplexingCount >= maxMultiplex || (currentMaxUsageCount > 0 && usageCount >= currentMaxUsageCount))
                    return false;

                if (state.compareAndSet(encoded, usageCount + 1, multiplexingCount + 1))
                    return true;
            }
        }

        /**
         * Try to release the entry if possible by decrementing the multiplexing
         * count unless the entity is closed.
         * @return true if the entry was released,
         * false if {@link #tryRemove()} should be called.
         */
        boolean tryRelease()
        {
            int newMultiplexingCount;
            int usageCount;
            while (true)
            {
                long encoded = state.get();
                usageCount = AtomicBiInteger.getHi(encoded);
                boolean closed = usageCount < 0;
                if (closed)
                    return false;

                newMultiplexingCount = AtomicBiInteger.getLo(encoded) - 1;
                if (newMultiplexingCount < 0)
                    throw new IllegalStateException("Cannot release an already released entry");

                if (state.compareAndSet(encoded, usageCount, newMultiplexingCount))
                    break;
            }

            int currentMaxUsageCount = maxUsageCount;
            boolean overUsed = currentMaxUsageCount > 0 && usageCount >= currentMaxUsageCount;
            return !(overUsed && newMultiplexingCount == 0);
        }

        public boolean isOverUsed()
        {
            int currentMaxUsageCount = maxUsageCount;
            int usageCount = state.getHi();
            return currentMaxUsageCount > 0 && usageCount >= currentMaxUsageCount;
        }

        /**
         * Try to mark the entry as removed.
         * @return true if the entry has to be removed from the containing pool, false otherwise.
         */
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
                {
                    if (usageCount == Integer.MIN_VALUE)
                        pending.decrementAndGet();
                    return newMultiplexCount == 0;
                }
            }
        }

        public boolean isClosed()
        {
            return state.getHi() < 0;
        }

        public boolean isIdle()
        {
            long encoded = state.get();
            return AtomicBiInteger.getHi(encoded) >= 0 && AtomicBiInteger.getLo(encoded) == 0;
        }

        public boolean isInUse()
        {
            long encoded = state.get();
            return AtomicBiInteger.getHi(encoded) >= 0 && AtomicBiInteger.getLo(encoded) > 0;
        }

        public int getUsageCount()
        {
            return Math.max(state.getHi(), 0);
        }

        @Override
        public String toString()
        {
            long encoded = state.get();
            return String.format("%s@%x{usage=%d/%d,multiplex=%d/%d,pooled=%s}",
                getClass().getSimpleName(),
                hashCode(),
                AtomicBiInteger.getHi(encoded),
                getMaxUsageCount(),
                AtomicBiInteger.getLo(encoded),
                getMaxMultiplex(),
                pooled);
        }
    }

    /** A pluggable strategy to optimize pool acquisition
     * @param <T> The type of the items in the pool
     */
    public interface Strategy<T>
    {
        /** Acquire an entry
         * @param entries The list of entries known to the pool. This may be concurrently modified.
         * @return An acquired entry or null if none can be acquired by this strategy
         */
        Pool<T>.Entry acquire(List<Pool<T>.Entry> entries);

        /**
         * Notification an entry has been release.  The notification comes after the entry
         * has been put back in the pool and it may already have been reacquired before or during this call.
         * @param entries The list of entries known to the pool. This may be concurrently modified.
         * @param entry The entry to be released
         */
        default boolean release(List<Pool<T>.Entry> entries, Pool<T>.Entry entry)
        {
            return entry.tryRelease();
        }

        default void reserve(List<Pool<T>.Entry> entries, Pool<T>.Entry entry)
        {
            entries.add(entry);
        }

        default boolean remove(List<Pool<T>.Entry> entries, Pool<T>.Entry entry)
        {
            if (!entry.tryRemove())
            {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Attempt to remove an object from the pool that is still in use: {}", entry);
                return false;
            }

            boolean removed = entries.remove(entry);
            if (!removed)
            {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("Attempt to remove an object from the pool that does not exist: {}", entry);
            }
            return removed;
        }
    }
    
    public static class CompositeStrategy<T> implements Strategy<T>
    {
        final Strategy<T> planA;
        final Strategy<T> planB;

        public CompositeStrategy(Strategy<T> planA, Strategy<T> planB)
        {
            Objects.requireNonNull(planA);
            Objects.requireNonNull(planB);
            this.planA = planA;
            this.planB = planB;
        }

        @Override
        public Pool<T>.Entry acquire(List<Pool<T>.Entry> entries)
        {
            Pool<T>.Entry entry = planA.acquire(entries);
            return entry == null ? planB.acquire(entries) : entry;
        }

        @Override
        public boolean release(List<Pool<T>.Entry> entries, Pool<T>.Entry entry)
        {
            if (planA.release(entries, entry))
                return true;
            if (entry.isOverUsed())
                return false;
            return planB.release(entries, entry);
        }

        @Override
        public boolean remove(List<Pool<T>.Entry> entries, Pool<T>.Entry entry)
        {
            return planA.remove(entries, entry) || planB.remove(entries, entry);
        }
    }

    public static class SearchStrategy<T> implements Strategy<T>
    {
        @Override
        public Pool<T>.Entry acquire(List<Pool<T>.Entry> entries)
        {
            for (Pool<T>.Entry e : entries)
            {
                if (e.tryAcquire())
                    return e;
            }
            return null;
        }
    }

    public static class ThreadLocalStrategy<T> implements Strategy<T>
    {
        private final ThreadLocal<Pool<T>.Entry> last;

        ThreadLocalStrategy()
        {
            last = new ThreadLocal<>();
        }

        @Override
        public Pool<T>.Entry acquire(List<Pool<T>.Entry> entries)
        {
            Pool<T>.Entry entry = last.get();
            if (entry != null && entry.tryAcquire())
                return entry;
            return null;
        }

        @Override
        public boolean release(List<Pool<T>.Entry> entries, Pool<T>.Entry entry)
        {
            if (entry.tryRelease())
            {
                last.set(entry);
                return true;
            }
            return false;
        }
    }

    public static class ThreadLocalListStrategy<T> implements Strategy<T>
    {
        private final ThreadLocal<List<Pool<T>.Entry>> cache;
        private final int cacheSize;

        ThreadLocalListStrategy(int size)
        {
            this.cacheSize = size;
            this.cache = ThreadLocal.withInitial(() -> new ArrayList<>(cacheSize));
        }

        @Override
        public Pool<T>.Entry acquire(List<Pool<T>.Entry> entries)
        {
            List<Pool<T>.Entry> cachedList = cache.get();
            while (!cachedList.isEmpty())
            {
                Pool<T>.Entry cachedEntry = cachedList.remove(cachedList.size() - 1);
                if (cachedEntry.tryAcquire())
                    return cachedEntry;
            }
            return null;
        }

        @Override
        public boolean release(List<Pool<T>.Entry> entries, Pool<T>.Entry entry)
        {
            if (entry.tryRelease())
            {
                List<Pool<T>.Entry> cachedList = cache.get();
                if (cachedList.size() < cacheSize)
                    cachedList.add(entry);
                return true;
            }
            return false;
        }
    }

    private abstract static class IndexedCached<T> implements Strategy<T>
    {
        @Override
        public Pool<T>.Entry acquire(List<Pool<T>.Entry> entries)
        {
            int size = entries.size();
            if (size == 0)
                return null;
            int i = nextIndex(size);
            try
            {
                Pool<T>.Entry entry = entries.get(i);
                if (entry != null && entry.tryAcquire())
                    return entry;
            }
            catch (Exception e)
            {
                // Could be out of bounds
                LOGGER.ignore(e);
            }
            return null;
        }

        protected abstract int nextIndex(int size);
    }

    public static class RandomStrategy<T> extends IndexedCached<T>
    {
        @Override
        protected int nextIndex(int size)
        {
            return ThreadLocalRandom.current().nextInt(size);
        }
    }

    public static class RoundRobinStrategy<T> extends IndexedCached<T>
    {
        AtomicInteger index = new AtomicInteger();

        @Override
        protected int nextIndex(int size)
        {
            return index.getAndUpdate(c -> Math.max(0, c + 1)) % size;
        }

        @Override
        public Pool<T>.Entry acquire(List<Pool<T>.Entry> entries)
        {
            int tries = entries.size();
            while (tries-- > 0)
            {
                Pool<T>.Entry entry = super.acquire(entries);
                if (entry != null)
                    return entry;
            }
            return null;
        }
    }

    public static class LeastRecentlyUsedStrategy<T> implements Strategy<T>
    {
        Queue<Pool<T>.Entry> lru = new ConcurrentLinkedQueue<>();

        @Override
        public Pool<T>.Entry acquire(List<Pool<T>.Entry> entries)
        {
            while (true)
            {
                Pool<T>.Entry entry = lru.poll();
                if (entry == null)
                    return null;
                if (entry.tryAcquire())
                    return entry;
            }
        }

        @Override
        public boolean release(List<Pool<T>.Entry> entries, Pool<T>.Entry entry)
        {
            if (entry.tryRelease())
            {
                lru.add(entry);
                return true;
            }
            return false;
        }
    }
}
