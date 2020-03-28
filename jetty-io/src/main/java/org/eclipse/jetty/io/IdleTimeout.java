//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An Abstract implementation of an Idle Timeout.
 * <p>
 * This implementation is optimised that timeout operations are not cancelled on
 * every operation. Rather timeout are allowed to expire and a check is then made
 * to see when the last operation took place.  If the idle timeout has not expired,
 * the timeout is rescheduled for the earliest possible time a timeout could occur.
 */
public abstract class IdleTimeout
{
    private static final Logger LOG = LoggerFactory.getLogger(IdleTimeout.class);
    private final Scheduler _scheduler;
    private final AtomicReference<Scheduler.Task> _timeout = new AtomicReference<>();
    private volatile long _idleTimeout;
    private volatile long _idleTimestamp = System.nanoTime();

    /**
     * @param scheduler A scheduler used to schedule checks for the idle timeout.
     */
    public IdleTimeout(Scheduler scheduler)
    {
        _scheduler = scheduler;
    }

    public Scheduler getScheduler()
    {
        return _scheduler;
    }

    /**
     * @return the period of time, in milliseconds, that this object was idle
     */
    public long getIdleFor()
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - _idleTimestamp);
    }

    /**
     * @return the idle timeout in milliseconds
     * @see #setIdleTimeout(long)
     */
    public long getIdleTimeout()
    {
        return _idleTimeout;
    }

    /**
     * <p>Sets the idle timeout in milliseconds.</p>
     * <p>A value that is less than or zero disables the idle timeout checks.</p>
     *
     * @param idleTimeout the idle timeout in milliseconds
     * @see #getIdleTimeout()
     */
    public void setIdleTimeout(long idleTimeout)
    {
        long old = _idleTimeout;
        _idleTimeout = idleTimeout;

        // Do we have an old timeout
        if (old > 0)
        {
            // if the old was less than or equal to the new timeout, then nothing more to do
            if (old <= idleTimeout)
                return;

            // old timeout is too long, so cancel it.
            deactivate();
        }

        // If we have a new timeout, then check and reschedule
        if (isOpen())
            activate();
    }

    /**
     * This method should be called when non-idle activity has taken place.
     */
    public void notIdle()
    {
        _idleTimestamp = System.nanoTime();
    }

    private void idleCheck()
    {
        long idleLeft = checkIdleTimeout();
        if (idleLeft >= 0)
            scheduleIdleTimeout(idleLeft > 0 ? idleLeft : getIdleTimeout());
    }

    private void scheduleIdleTimeout(long delay)
    {
        Scheduler.Task newTimeout = null;
        if (isOpen() && delay > 0 && _scheduler != null)
            newTimeout = _scheduler.schedule(this::idleCheck, delay, TimeUnit.MILLISECONDS);
        Scheduler.Task oldTimeout = _timeout.getAndSet(newTimeout);
        if (oldTimeout != null)
            oldTimeout.cancel();
    }

    public void onOpen()
    {
        activate();
    }

    private void activate()
    {
        if (_idleTimeout > 0)
            idleCheck();
    }

    public void onClose()
    {
        deactivate();
    }

    private void deactivate()
    {
        Scheduler.Task oldTimeout = _timeout.getAndSet(null);
        if (oldTimeout != null)
            oldTimeout.cancel();
    }

    protected long checkIdleTimeout()
    {
        if (isOpen())
        {
            long idleTimestamp = _idleTimestamp;
            long idleElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - idleTimestamp);
            long idleTimeout = getIdleTimeout();
            long idleLeft = idleTimeout - idleElapsed;

            if (LOG.isDebugEnabled())
                LOG.debug("{} idle timeout check, elapsed: {} ms, remaining: {} ms", this, idleElapsed, idleLeft);

            if (idleTimeout > 0)
            {
                if (idleLeft <= 0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} idle timeout expired", this);
                    try
                    {
                        onIdleExpired(new TimeoutException("Idle timeout expired: " + idleElapsed + "/" + idleTimeout + " ms"));
                    }
                    finally
                    {
                        notIdle();
                    }
                }
            }

            return idleLeft >= 0 ? idleLeft : 0;
        }
        return -1;
    }

    /**
     * This abstract method is called when the idle timeout has expired.
     *
     * @param timeout a TimeoutException
     */
    protected abstract void onIdleExpired(TimeoutException timeout);

    /**
     * This abstract method should be called to check if idle timeouts
     * should still be checked.
     *
     * @return True if the entity monitored should still be checked for idle timeouts
     */
    public abstract boolean isOpen();
}
