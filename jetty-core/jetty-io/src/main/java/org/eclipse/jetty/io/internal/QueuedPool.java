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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;

import org.eclipse.jetty.util.Pool;

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
public class QueuedPool<P> implements Pool<P>
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

    @Override
    public Entry<P> acquire()
    {
        rwLock.readLock().lock();
        try
        {
            if (terminated)
                return null;
            QueuedEntry<P> entry = (QueuedEntry<P>)queue.poll();
            if (entry != null)
            {
                queueSize.decrementAndGet();
                entry.acquire();
            }
            return entry;
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

    private static class QueuedEntry<P> implements Entry<P>
    {
        private final QueuedPool<P> pool;
        // null/false -> reserved
        // null/true  -> terminated
        // val/false -> idle
        // val/true  -> in use
        private final AtomicMarkableReference<P> pooled = new AtomicMarkableReference<>(null, false);
        private P removedReference; // Only written before setting 'pooled', only read after getting 'pooled'.

        private QueuedEntry(QueuedPool<P> pool)
        {
            this.pool = pool;
        }

        @Override
        public boolean enable(P pooled, boolean acquire)
        {
            Objects.requireNonNull(pooled);
            boolean[] inUse = new boolean[1];
            P p = this.pooled.get(inUse);
            if (p != null)
            {
                if (pool.isTerminated())
                    return false;
                throw new IllegalStateException("Entry already enabled " + this + " for " + pool);
            }
            if (inUse[0])
                return false; // terminated

            this.pooled.set(pooled, acquire);
            if (acquire)
            {
                if (pool.isTerminated())
                {
                    this.pooled.set(null, false);
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
            P reference = pooled.getReference();
            return reference != null ? reference : removedReference;
        }

        void acquire()
        {
            boolean[] inUse = new boolean[1];
            P p = pooled.get(inUse);
            if (p == null || inUse[0])
                return; // terminated
            pooled.set(p, true);
        }

        @Override
        public boolean release()
        {
            boolean[] inUse = new boolean[1];
            P p = pooled.get(inUse);
            if (p == null || !inUse[0])
                return false;
            pooled.set(p, false);
            return pool.requeue(this);
        }

        @Override
        public boolean remove()
        {
            boolean[] inUse = new boolean[1];
            P p = pooled.get(inUse);
            if (p == null && inUse[0])
                return false;
            removedReference = p;
            pooled.set(null, true);
            return true;
        }

        @Override
        public boolean isReserved()
        {
            return pooled.getReference() == null;
        }

        @Override
        public boolean isIdle()
        {
            return !pooled.isMarked();
        }

        @Override
        public boolean isInUse()
        {
            return pooled.isMarked();
        }

        @Override
        public boolean isTerminated()
        {
            boolean[] inUse = new boolean[1];
            P p = pooled.get(inUse);
            return p == null && inUse[0];
        }
    }
}
