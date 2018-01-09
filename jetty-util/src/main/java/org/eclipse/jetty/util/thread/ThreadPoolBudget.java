//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.util.thread;

import java.io.Closeable;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A budget of required thread usage, used to warn or error for insufficient configured threads.</p>
 *
 * @see ThreadPool.SizedThreadPool#getThreadPoolBudget()
 */
public class ThreadPoolBudget
{
    static final Logger LOG = Log.getLogger(ThreadPoolBudget.class);

    public interface Lease extends Closeable
    {
        int getThreads();
    }

    /**
     * An allocation of threads
     */
    public class Leased implements Lease
    {
        final Object leasee;
        final int threads;

        private Leased(Object leasee,int threads)
        {
            this.leasee = leasee;
            this.threads = threads;
        }

        @Override
        public int getThreads()
        {
            return threads;
        }

        @Override
        public void close()
        {
            info.remove(this);
            allocations.remove(this);
            warned.set(false);
        }
    }

    private static final Lease NOOP_LEASE = new Lease()
    {
        @Override
        public void close() throws IOException
        {
        }

        @Override
        public int getThreads()
        {
            return 0;
        }
    };

    final ThreadPool.SizedThreadPool pool;
    final Set<Leased> allocations = new CopyOnWriteArraySet<>();
    final Set<Leased> info = new CopyOnWriteArraySet<>();
    final AtomicBoolean warned = new AtomicBoolean();
    final int warnAt;

    /**
     * Construct a budget for a SizedThreadPool, with the warning level set by heuristic.
     * @param pool The pool to budget thread allocation for.
     */
    public ThreadPoolBudget(ThreadPool.SizedThreadPool pool)
    {
        this(pool,Runtime.getRuntime().availableProcessors());
    }

    /**
     * @param pool The pool to budget thread allocation for.
     * @param warnAt The level of free threads at which a warning is generated.
     */
    public ThreadPoolBudget(ThreadPool.SizedThreadPool pool, int warnAt)
    {
        this.pool = pool;
        this.warnAt = warnAt;
    }

    public ThreadPool.SizedThreadPool getSizedThreadPool()
    {
        return pool;
    }

    public void reset()
    {
        allocations.clear();
        info.clear();
        warned.set(false);
    }

    public Lease leaseTo(Object leasee, int threads)
    {
        Leased lease = new Leased(leasee,threads);
        allocations.add(lease);
        check();
        return lease;
    }

    /**
     * Check registered allocations against the budget.
     * @throws IllegalStateException if insufficient threads are configured.
     */
    public void check() throws IllegalStateException
    {
        int required = allocations.stream()
            .mapToInt(Lease::getThreads)
            .sum();
        int maximum = pool.getMaxThreads();
        int actual = maximum - required;

        if (actual <= 0)
        {
            infoOnLeases();
            throw new IllegalStateException(String.format("Insufficient configured threads: required=%d < max=%d for %s", required, maximum, pool));
        }

        if (actual < warnAt)
        {
            infoOnLeases();
            if (warned.compareAndSet(false,true))
                LOG.warn("Low configured threads: (max={} - required={})={} < warnAt={} for {}", maximum, required, actual, warnAt, pool);
        }
    }

    private void infoOnLeases()
    {
        allocations.stream().filter(lease->!info.contains(lease))
            .forEach(lease->{
                info.add(lease);
                LOG.info("{} requires {} threads from {}",lease.leasee,lease.getThreads(),pool);
            });
    }

    public static Lease leaseFrom(Executor executor, Object leasee, int threads)
    {
        if (executor instanceof ThreadPool.SizedThreadPool)
        {
            ThreadPoolBudget budget = ((ThreadPool.SizedThreadPool)executor).getThreadPoolBudget();
            if (budget!=null)
                return budget.leaseTo(leasee,threads);
        }
        return NOOP_LEASE;
    }
}
