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

package org.eclipse.jetty.util.thread;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A utility class to perform periodic sweeping of resources.</p>
 * <p>{@link Sweepable} resources may be added to or removed from a
 * {@link Sweeper} and the resource implementation decides whether
 * it should be swept or not.</p>
 * <p>If a {@link Sweepable} resources is itself a container of
 * other sweepable resources, it will forward the sweep operation
 * to children resources, and so on recursively.</p>
 * <p>Typical usage is to add {@link Sweeper} as a bean to an existing
 * container:</p>
 * <pre>
 * Server server = new Server();
 * server.addBean(new Sweeper(), true);
 * server.start();
 * </pre>
 * Code that knows it has sweepable resources can then lookup the
 * {@link Sweeper} and offer the sweepable resources to it:
 * <pre>
 * class MyComponent implements Sweeper.Sweepable
 * {
 *     private final long creation;
 *     private volatile destroyed;
 *
 *     MyComponent(Server server)
 *     {
 *         this.creation = System.nanoTime();
 *         Sweeper sweeper = server.getBean(Sweeper.class);
 *         sweeper.offer(this);
 *     }
 *
 *     void destroy()
 *     {
 *         destroyed = true;
 *     }
 *
 *     &#64;Override
 *     public boolean sweep()
 *     {
 *         return destroyed;
 *     }
 * }
 * </pre>
 */
public class Sweeper extends AbstractLifeCycle implements Runnable
{
    private static final Logger LOG = LoggerFactory.getLogger(Sweeper.class);

    private final AtomicReference<List<Sweepable>> items = new AtomicReference<>();
    private final AtomicReference<Scheduler.Task> task = new AtomicReference<>();
    private final Scheduler scheduler;
    private final long period;

    public Sweeper(Scheduler scheduler, long period)
    {
        this.scheduler = scheduler;
        this.period = period;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        items.set(new CopyOnWriteArrayList<Sweepable>());
        activate();
    }

    @Override
    protected void doStop() throws Exception
    {
        deactivate();
        items.set(null);
        super.doStop();
    }

    public int getSize()
    {
        List<Sweepable> refs = items.get();
        return refs == null ? 0 : refs.size();
    }

    public boolean offer(Sweepable sweepable)
    {
        List<Sweepable> refs = items.get();
        if (refs == null)
            return false;
        refs.add(sweepable);
        if (LOG.isDebugEnabled())
            LOG.debug("Resource offered {}", sweepable);
        return true;
    }

    public boolean remove(Sweepable sweepable)
    {
        List<Sweepable> refs = items.get();
        return refs != null && refs.remove(sweepable);
    }

    @Override
    public void run()
    {
        List<Sweepable> refs = items.get();
        if (refs == null)
            return;
        for (Sweepable sweepable : refs)
        {
            try
            {
                if (sweepable.sweep())
                {
                    refs.remove(sweepable);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Resource swept {}", sweepable);
                }
            }
            catch (Throwable x)
            {
                LOG.info("Exception while sweeping {}", sweepable, x);
            }
        }
        activate();
    }

    private void activate()
    {
        if (isRunning())
        {
            Scheduler.Task t = scheduler.schedule(this, period, TimeUnit.MILLISECONDS);
            if (LOG.isDebugEnabled())
                LOG.debug("Scheduled in {} ms sweep task {}", period, t);
            task.set(t);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Skipping sweep task scheduling");
        }
    }

    private void deactivate()
    {
        Scheduler.Task t = task.getAndSet(null);
        if (t != null)
        {
            boolean cancelled = t.cancel();
            if (LOG.isDebugEnabled())
                LOG.debug("Cancelled ({}) sweep task {}", cancelled, t);
        }
    }

    /**
     * <p>A {@link Sweepable} resource implements this interface to
     * signal to a {@link Sweeper} or to a parent container if it
     * needs to be swept or not.</p>
     * <p>Typical implementations will check their own internal state
     * and return true or false from {@link #sweep()} to indicate
     * whether they should be swept.</p>
     */
    public interface Sweepable
    {
        /**
         * @return whether this resource should be swept
         */
        public boolean sweep();
    }
}
