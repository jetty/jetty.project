//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.io;

import static java.lang.Long.MAX_VALUE;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An Abstract implementation of a Timeout Task.
 * <p>
 * This implementation is optimized assuming that the timeout will mostly
 * be cancelled and then reused with a similar value.
 */
public abstract class CyclicTimeoutTask
{
    private final Scheduler _scheduler;
    private final AtomicReference<Schedule> _scheduled = new AtomicReference<>();
    private final AtomicLong _expireAtNanos = new AtomicLong(Long.MAX_VALUE);
    private volatile Scheduler.Task _task;


    /**
     * @param scheduler A scheduler used to schedule checks for the idle timeout.
     */
    public CyclicTimeoutTask(Scheduler scheduler)
    {
        _scheduler = scheduler;
    }

    public Scheduler getScheduler()
    {
        return _scheduler;
    }
    
    public void schedule(long delay, TimeUnit units)
    {
        long now = System.nanoTime();
        long expireAtNanos = now + units.toNanos(delay);
    
        if (!_expireAtNanos.compareAndSet(MAX_VALUE,expireAtNanos))
            throw new IllegalStateException("Timeout pending");
    
        Schedule schedule = _scheduled.get();
        if (schedule==null || schedule._scheduledAt>expireAtNanos)
            _scheduled.compareAndSet(schedule,new Schedule(now,expireAtNanos,schedule));
    }
    
    public boolean reschedule(long delay, TimeUnit units)
    {
        long now = System.nanoTime();
        long expireAtNanos = now + units.toNanos(delay);
    
        while(true)
        {
            long expireAt = _expireAtNanos.get();
            if (expireAt==MAX_VALUE)
                return false;

            if (_expireAtNanos.compareAndSet(expireAt,expireAtNanos))
            {
                Schedule schedule = _scheduled.get();
                if (schedule==null || schedule._scheduledAt>expireAtNanos)
                    _scheduled.compareAndSet(schedule,new Schedule(now,expireAtNanos,schedule));
                return true;
            }
        }
    }
    
    
    public boolean cancel()
    {
        return _expireAtNanos.getAndSet(MAX_VALUE)!=MAX_VALUE;
    }
    
    protected abstract void onTimeoutExpired();

    public void destroy()
    {
        cancel();
        Scheduler.Task task = _task;
        if (task!=null)
            task.cancel();
        Schedule schedule = _scheduled.getAndSet(null);
        while (schedule!=null)
        {
            schedule._task.cancel();
            schedule = schedule._next;
        }
    }
    
    private class Schedule implements Runnable
    {
        final long _scheduledAt;
        final Scheduler.Task _task;
        final Schedule _next;
        
        Schedule(long now, long scheduledAt, Schedule next)
        {
            _scheduledAt = scheduledAt;
            _task = _scheduler.schedule(this,scheduledAt-now,TimeUnit.NANOSECONDS);
            _next = next;
        }
        
        @Override
        public void run()
        {
            while(true)
            {
                long now = System.nanoTime();
                long expireAt = _expireAtNanos.get();
                if (expireAt==MAX_VALUE)
                    return;

                if (expireAt<now)
                {
                    if (_expireAtNanos.compareAndSet(expireAt,MAX_VALUE) &&
                        _scheduled.compareAndSet(this,_next))
                    {
                        onTimeoutExpired();
                        return;
                    }
                }
                else
                {
                    Schedule next = _next;
                    if (next==null || next._scheduledAt>expireAt)
                        next = new Schedule(now,expireAt,next);
                    if (_scheduled.compareAndSet(this,next))
                        return;
                }
            }
        }
    }
    
}
