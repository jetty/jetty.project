//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A budget of required thread usage, used to warn or error for insufficient configured threads.</p>
 *
 * @see ThreadPool.SizedThreadPool#getThreadPoolBudget()
 */
@ManagedObject
public class ThreadPoolBudget
{
    private static final Logger LOG = Log.getLogger(ThreadPoolBudget.class);

    public interface Lease extends Closeable
    {
        int getThreads();
    }

    /**
     * An allocation of threads
     */
    public class Leased implements Lease
    {
        private final Object leasee;
        private final int threads;

        private Leased(Object leasee, int threads)
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
            leases.remove(this);
            warned.set(false);
        }
    }

    private static final Lease NOOP_LEASE = new Lease()
    {
        @Override
        public void close()
        {
        }

        @Override
        public int getThreads()
        {
            return 0;
        }
    };

    private final Set<Leased> leases = new CopyOnWriteArraySet<>();
    private final AtomicBoolean warned = new AtomicBoolean();
    private final ThreadPool.SizedThreadPool pool;
    private final int warnAt;

    /**
     * Construct a budget for a SizedThreadPool.
     *
     * @param pool The pool to budget thread allocation for.
     */
    public ThreadPoolBudget(ThreadPool.SizedThreadPool pool)
    {
        this.pool = pool;
        this.warnAt = -1;
    }

    /**
     * @param pool The pool to budget thread allocation for.
     * @param warnAt The level of free threads at which a warning is generated.
     */
    @Deprecated
    public ThreadPoolBudget(ThreadPool.SizedThreadPool pool, int warnAt)
    {
        this.pool = pool;
        this.warnAt = warnAt;
    }

    public ThreadPool.SizedThreadPool getSizedThreadPool()
    {
        return pool;
    }

    @ManagedAttribute("the number of threads leased to components")
    public int getLeasedThreads()
    {
        return leases.stream()
            .mapToInt(Lease::getThreads)
            .sum();
    }

    public void reset()
    {
        leases.clear();
        warned.set(false);
    }

    public Lease leaseTo(Object leasee, int threads)
    {
        Leased lease = new Leased(leasee, threads);
        leases.add(lease);
        try
        {
            check(pool.getMaxThreads());
            return lease;
        }
        catch (IllegalStateException e)
        {
            lease.close();
            throw e;
        }
    }

    /**
     * <p>Checks leases against the given number of {@code maxThreads}.</p>
     *
     * @param maxThreads A proposed change to the maximum threads to check.
     * @return true if passes check, false if otherwise (see logs for details)
     * @throws IllegalStateException if insufficient threads are configured.
     */
    public boolean check(int maxThreads) throws IllegalStateException
    {
        int required = getLeasedThreads();
        int left = maxThreads - required;
        if (left <= 0)
        {
            printInfoOnLeases();
            throw new IllegalStateException(String.format("Insufficient configured threads: required=%d < max=%d for %s", required, maxThreads, pool));
        }

        if (left < warnAt)
        {
            if (warned.compareAndSet(false, true))
            {
                printInfoOnLeases();
                LOG.info("Low configured threads: (max={} - required={})={} < warnAt={} for {}", maxThreads, required, left, warnAt, pool);
            }
            return false;
        }
        return true;
    }

    private void printInfoOnLeases()
    {
        leases.forEach(lease -> LOG.info("{} requires {} threads from {}", lease.leasee, lease.getThreads(), pool));
    }

    public static Lease leaseFrom(Executor executor, Object leasee, int threads)
    {
        if (executor instanceof ThreadPool.SizedThreadPool)
        {
            ThreadPoolBudget budget = ((ThreadPool.SizedThreadPool)executor).getThreadPoolBudget();
            if (budget != null)
                return budget.leaseTo(leasee, threads);
        }
        return NOOP_LEASE;
    }
}
