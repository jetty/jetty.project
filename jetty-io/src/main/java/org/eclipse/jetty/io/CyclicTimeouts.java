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

package org.eclipse.jetty.io;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>An implementation of a timeout that manages many {@link Expirable expirable} entities whose
 * timeouts are mostly cancelled or re-scheduled.</p>
 * <p>A typical scenario is for a parent entity to manage the timeouts of many children entities.</p>
 * <p>When a new entity is created, call {@link #schedule(Expirable)} with the new entity so that
 * this instance can be aware and manage the timeout of the new entity.</p>
 * <p>Eventually, this instance wakes up and iterates over the entities provided by {@link #iterator()}.
 * During the iteration, each entity:</p>
 * <ul>
 *   <li>may never expire (see {@link Expirable#getExpireNanoTime()}; the entity is ignored</li>
 *   <li>may be expired; {@link #onExpired(Expirable)} is called with that entity as parameter</li>
 *   <li>may expire at a future time; the iteration records the earliest expiration time among
 *   all non-expired entities</li>
 * </ul>
 * <p>When the iteration is complete, this instance is re-scheduled with the earliest expiration time
 * calculated during the iteration.</p>
 *
 * @param <T> the {@link Expirable} entity type
 * @see CyclicTimeout
 */
public abstract class CyclicTimeouts<T extends CyclicTimeouts.Expirable> implements Destroyable
{
    private static final Logger LOG = LoggerFactory.getLogger(CyclicTimeouts.class);

    private final AtomicLong earliestTimeout = new AtomicLong(Long.MAX_VALUE);
    private final CyclicTimeout cyclicTimeout;

    public CyclicTimeouts(Scheduler scheduler)
    {
        cyclicTimeout = new Timeouts(scheduler);
    }

    /**
     * @return the entities to iterate over when this instance expires
     */
    protected abstract Iterator<T> iterator();

    /**
     * <p>Invoked during the iteration when the given entity is expired.</p>
     * <p>This method may be invoked multiple times, and even concurrently,
     * for the same expirable entity and therefore the expiration of the
     * entity, if any, should be an idempotent action.</p>
     *
     * @param expirable the entity that is expired
     * @return whether the entity should be removed from the iterator via {@link Iterator#remove()}
     */
    protected abstract boolean onExpired(T expirable);

    private void onTimeoutExpired()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Timeouts check for {}", this);

        long now = NanoTime.now();
        long earliest = Long.MAX_VALUE;
        // Reset the earliest timeout so we can expire again.
        // A concurrent call to schedule(long) may lose an
        // earliest value, but the corresponding entity will
        // be seen during the iteration below.
        earliestTimeout.set(earliest);

        Iterator<T> iterator = iterator();
        if (iterator == null)
            return;

        // Scan the entities to abort expired entities
        // and to find the entity that expires the earliest.
        while (iterator.hasNext())
        {
            T expirable = iterator.next();
            long expiresAt = expirable.getExpireNanoTime();

            if (LOG.isDebugEnabled())
                LOG.debug("Entity {} expires in {} ms for {}", expirable, NanoTime.millisElapsed(now, expiresAt), this);

            if (expiresAt == -1)
                continue;

            if (NanoTime.isBeforeOrSame(expiresAt, now))
            {
                boolean remove = onExpired(expirable);
                if (LOG.isDebugEnabled())
                    LOG.debug("Entity {} expired, remove={} for {}", expirable, remove, this);
                if (remove)
                    iterator.remove();
                continue;
            }

            earliest = Math.min(earliest, NanoTime.elapsed(now, expiresAt));
        }

        if (earliest < Long.MAX_VALUE)
            schedule(now + earliest);
    }

    /**
     * <p>Manages the timeout of a new entity.</p>
     *
     * @param expirable the new entity to manage the timeout for
     */
    public void schedule(T expirable)
    {
        long expiresAt = expirable.getExpireNanoTime();
        if (expiresAt < Long.MAX_VALUE)
            schedule(expiresAt);
    }

    private void schedule(long expiresAt)
    {
        // Schedule a timeout for the earliest entity that may expire.
        // When the timeout expires, scan the entities for the next
        // earliest entity that may expire, and reschedule a new timeout.
        long prevEarliest = earliestTimeout.getAndUpdate(t -> NanoTime.isBefore(t, expiresAt) ? t : expiresAt);
        long expires = expiresAt;
        while (NanoTime.isBefore(expires, prevEarliest))
        {
            // A new entity expires earlier than previous entities, schedule it.
            long delay = Math.max(0, NanoTime.until(expires));
            if (LOG.isDebugEnabled())
                LOG.debug("Scheduling timeout in {} ms for {}", TimeUnit.NANOSECONDS.toMillis(delay), this);
            schedule(cyclicTimeout, delay, TimeUnit.NANOSECONDS);

            // If we lost a race and overwrote a schedule() with an earlier time, then that earlier time
            // is remembered by earliestTimeout, in which case we will loop and set it again ourselves.
            prevEarliest = expires;
            expires = earliestTimeout.get();
        }
    }

    @Override
    public void destroy()
    {
        cyclicTimeout.destroy();
    }

    boolean schedule(CyclicTimeout cyclicTimeout, long delay, TimeUnit unit)
    {
        return cyclicTimeout.schedule(delay, unit);
    }

    /**
     * <p>An entity that may expire.</p>
     */
    public interface Expirable
    {
        /**
         * <p>Returns the expiration time in nanoseconds.</p>
         * <p>The value to return must be calculated taking into account the current nanoTime,
         * for example:</p>
         * {@code expireNanoTime = NanoTime.now() + timeoutNanos}
         * <p>Returning {@link Long#MAX_VALUE} indicates that this entity does not expire.</p>
         *
         * @return the expiration time in nanoseconds, or {@link Long#MAX_VALUE} if this entity does not expire
         */
        public long getExpireNanoTime();
    }

    private class Timeouts extends CyclicTimeout
    {
        private Timeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        public void onTimeoutExpired()
        {
            CyclicTimeouts.this.onTimeoutExpired();
        }
    }
}
