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

package org.eclipse.jetty.io.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;

/**
 * <p>A {@link Queue} based implementation of {@link Pool}.</p>
 * <p>Entries are taken out of the pool when they are acquired
 * and they are added back when they are released which means
 * acquired and reserved entries do not account for the
 * {@code maxSize} calculation. This also means {@link QueuedPool}
 * is resistant to "release leaks".</p>
 * <p>This implementation does not support multiplexing.</p>
 *
 * @param <P> the type of the pooled objects
 */
public class QueuedPool<P> implements Pool<P>, Dumpable
{
    // All code that uses these three fields is fully thread-safe.
    private final int maxSize;
    private final Queue<Entry<P>> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();

    // Only the 'terminated' field is protected by the RW lock,
    // the other fields are totally ignored w.r.t the scope of this lock;
    // so when the read lock or the write lock is needed solely depends
    // on what is being done to the 'terminated' field.
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private boolean terminated;

    public QueuedPool(int maxSize)
    {
        this.maxSize = maxSize;
    }

    @Override
    public Entry<P> reserve()
    {
        rwLock.readLock().lock();
        try
        {
            if (terminated || queueSize.get() == maxSize)
                return null;
            return new QueuedEntry<>(this);
        }
        finally
        {
            rwLock.readLock().unlock();
        }
    }

    private boolean requeue(Entry<P> entry)
    {
        rwLock.readLock().lock();
        try
        {
            while (true)
            {
                int size = queueSize.get();
                if (terminated || size == maxSize)
                    return false;
                if (!queueSize.compareAndSet(size, size + 1))
                    continue;
                queue.add(entry);
                return true;
            }
        }
        finally
        {
            rwLock.readLock().unlock();
        }
    }

    private void remove(QueuedEntry<P> entry)
    {
        rwLock.readLock().lock();
        try
        {
            if (terminated)
                return;
            if (queue.remove(entry))
                queueSize.decrementAndGet();
        }
        finally
        {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Entry<P> acquire()
    {
        rwLock.readLock().lock();
        try
        {
            if (terminated)
                return null;
            while (true)
            {
                QueuedEntry<P> entry = (QueuedEntry<P>)queue.poll();
                if (entry == null)
                    return null;
                queueSize.decrementAndGet();
                if (entry.acquire())
                    return entry;
            }
        }
        finally
        {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public boolean isTerminated()
    {
        rwLock.readLock().lock();
        try
        {
            return terminated;
        }
        finally
        {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public Collection<Entry<P>> terminate()
    {
        rwLock.writeLock().lock();
        try
        {
            // Once 'terminated' has been set to true, no entry can be
            // added nor removed from the queue; the setting to true
            // as well as the copy and the clearing of the queue MUST be
            // atomic otherwise we may not return the exact list of entries
            // that remained in the pool when terminate() was called.
            terminated = true;
            Collection<Entry<P>> copy = new ArrayList<>(queue);
            queue.clear();
            copy.forEach(Entry::remove);
            queueSize.set(0);
            return copy;
        }
        finally
        {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public int size()
    {
        return queueSize.get();
    }

    @Override
    public int getMaxSize()
    {
        return maxSize;
    }

    @Override
    public Stream<Entry<P>> stream()
    {
        return queue.stream();
    }

    @Override
    public int getReservedCount()
    {
        return 0;
    }

    @Override
    public int getIdleCount()
    {
        return size();
    }

    @Override
    public int getInUseCount()
    {
        return 0;
    }

    @Override
    public int getTerminatedCount()
    {
        return 0;
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, new DumpableCollection("entries", queue));
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[size=%d,max=%d,terminated=%b]",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            size(),
            getMaxSize(),
            isTerminated());
    }

    private static class QueuedEntry<P> implements Entry<P>
    {
        private static final int RESERVED = 0;
        private static final int IDLE = 1;
        private static final int IN_USE = 2;
        private static final int TERMINATED = 3;

        private final QueuedPool<P> pool;
        private final AtomicInteger state = new AtomicInteger(RESERVED);
        // The pooled object. This is not volatile as it is set once and then never changed.
        // Other threads accessing must check the state field above first, so a good before/after
        // relationship exists to make a memory barrier.
        private P pooled;

        private QueuedEntry(QueuedPool<P> pool)
        {
            this.pool = pool;
        }

        @Override
        public boolean enable(P pooled, boolean acquire)
        {
            Objects.requireNonNull(pooled);

            int s = state.get();
            if (s != RESERVED)
            {
                if (s == TERMINATED || pool.isTerminated())
                    return false;
                throw new IllegalStateException("Entry already enabled " + this + " for " + pool);
            }

            this.pooled = pooled;

            if (!state.compareAndSet(RESERVED, acquire ? IN_USE : IDLE))
                throw new IllegalStateException("Entry already enabled " + this + " for " + pool);

            if (acquire)
            {
                if (pool.isTerminated())
                {
                    state.set(TERMINATED);
                    return false;
                }
                return true;
            }
            else
            {
                return pool.requeue(this);
            }
        }

        @Override
        public P getPooled()
        {
            return pooled;
        }

        private boolean acquire()
        {
            return state.compareAndSet(IDLE, IN_USE);
        }

        @Override
        public boolean release()
        {
            return state.compareAndSet(IN_USE, IDLE) && pool.requeue(this);
        }

        @Override
        public boolean remove()
        {
            int s = state.get();
            if (s == TERMINATED)
                return false;
            if (state.compareAndSet(s, TERMINATED))
            {
                if (s == IDLE)
                    pool.remove(this);
                return true;
            }
            return false;
        }

        @Override
        public boolean isReserved()
        {
            return state.get() == RESERVED;
        }

        @Override
        public boolean isIdle()
        {
            return state.get() == IDLE;
        }

        @Override
        public boolean isInUse()
        {
            return state.get() == IN_USE;
        }

        @Override
        public boolean isTerminated()
        {
            return state.get() == TERMINATED;
        }
    }
}
