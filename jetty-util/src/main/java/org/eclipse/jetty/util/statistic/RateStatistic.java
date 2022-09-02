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

package org.eclipse.jetty.util.statistic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * <p>Statistics on a time sequence rate.</p>
 * <p>Calculates the rate at which the {@link #record()} method is called
 * over the configured period, retaining also the total count and maximum
 * rate achieved.</p>
 * <p>The implementation keeps a Deque of timestamps for all records for
 * the last time period, so this method is not suitable for large rates
 * unless a small time period is used.</p>
 */
public class RateStatistic
{
    private final AutoLock _lock = new AutoLock();
    private final Deque<Long> _samples = new ArrayDeque<>();
    private final long _nanoPeriod;
    private final TimeUnit _units;
    private long _max;
    private long _count;

    public RateStatistic(long period, TimeUnit units)
    {
        _nanoPeriod = TimeUnit.NANOSECONDS.convert(period, units);
        _units = units;
    }

    public long getPeriod()
    {
        return _units.convert(_nanoPeriod, TimeUnit.NANOSECONDS);
    }

    public TimeUnit getUnits()
    {
        return _units;
    }

    /**
     * Resets the statistics.
     */
    public void reset()
    {
        try (AutoLock l = _lock.lock())
        {
            _samples.clear();
            _max = 0;
            _count = 0;
        }
    }

    private void update()
    {
        update(NanoTime.now());
    }

    private void update(long now)
    {
        Long head = _samples.peekFirst();
        while (head != null && NanoTime.elapsed(head, now) > _nanoPeriod)
        {
            _samples.removeFirst();
            head = _samples.peekFirst();
        }
    }

    protected void age(long period, TimeUnit units)
    {
        long increment = TimeUnit.NANOSECONDS.convert(period, units);
        try (AutoLock l = _lock.lock())
        {
            int size = _samples.size();
            for (int i = 0; i < size; i++)
            {
                _samples.addLast(_samples.removeFirst() - increment);
            }
            update();
        }
    }

    /**
     * Records a sample value.
     *
     * @return the number of records in the current period.
     */
    public int record()
    {
        long now = NanoTime.now();
        try (AutoLock l = _lock.lock())
        {
            _count++;
            _samples.add(now);
            update(now);
            int rate = _samples.size();
            if (rate > _max)
                _max = rate;
            return rate;
        }
    }

    /**
     * @return the number of records in the current period
     */
    public int getRate()
    {
        try (AutoLock l = _lock.lock())
        {
            update();
            return _samples.size();
        }
    }

    /**
     * @return the max number of samples per period.
     */
    public long getMax()
    {
        try (AutoLock l = _lock.lock())
        {
            return _max;
        }
    }

    /**
     * @param units the units of the return
     * @return the age of the oldest sample in the requested units
     */
    public long getOldest(TimeUnit units)
    {
        try (AutoLock l = _lock.lock())
        {
            Long head = _samples.peekFirst();
            if (head == null)
                return -1;
            return units.convert(NanoTime.elapsedFrom(head), TimeUnit.NANOSECONDS);
        }
    }

    /**
     * @return the number of samples recorded
     */
    public long getCount()
    {
        try (AutoLock l = _lock.lock())
        {
            return _count;
        }
    }

    public String dump()
    {
        return dump(TimeUnit.MINUTES);
    }

    public String dump(TimeUnit units)
    {
        long now = NanoTime.now();
        try (AutoLock l = _lock.lock())
        {
            String samples = _samples.stream()
                .mapToLong(t -> units.convert(NanoTime.elapsed(t, now), TimeUnit.NANOSECONDS))
                .mapToObj(Long::toString)
                .collect(Collectors.joining(System.lineSeparator()));
            return String.format("%s%n%s", toString(now), samples);
        }
    }

    @Override
    public String toString()
    {
        return toString(NanoTime.now());
    }

    private String toString(long nanoTime)
    {
        try (AutoLock l = _lock.lock())
        {
            update(nanoTime);
            return String.format("%s@%x{count=%d,max=%d,rate=%d per %d %s}",
                getClass().getSimpleName(), hashCode(),
                _count, _max, _samples.size(),
                _units.convert(_nanoPeriod, TimeUnit.NANOSECONDS), _units);
        }
    }
}
