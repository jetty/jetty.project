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

package org.eclipse.jetty.util.statistic;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
        synchronized (this)
        {
            _samples.clear();
            _max = 0;
            _count = 0;
        }
    }

    private void update()
    {
        update(System.nanoTime());
    }

    private void update(long now)
    {
        long expire = now - _nanoPeriod;
        Long head = _samples.peekFirst();
        while (head != null && head < expire)
        {
            _samples.removeFirst();
            head = _samples.peekFirst();
        }
    }

    protected void age(long period, TimeUnit units)
    {
        long increment = TimeUnit.NANOSECONDS.convert(period, units);
        synchronized (this)
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
        long now = System.nanoTime();
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
        {
            Long head = _samples.peekFirst();
            if (head == null)
                return -1;
            return units.convert(System.nanoTime() - head, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * @return the number of samples recorded
     */
    public long getCount()
    {
        synchronized (this)
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
        long now = System.nanoTime();
        synchronized (this)
        {
            String samples = _samples.stream()
                .mapToLong(t -> units.convert(now - t, TimeUnit.NANOSECONDS))
                .mapToObj(Long::toString)
                .collect(Collectors.joining(System.lineSeparator()));
            return String.format("%s%n%s", toString(now), samples);
        }
    }

    @Override
    public String toString()
    {
        return toString(System.nanoTime());
    }

    private String toString(long nanoTime)
    {
        synchronized (this)
        {
            update(nanoTime);
            return String.format("%s@%x{count=%d,max=%d,rate=%d per %d %s}",
                getClass().getSimpleName(), hashCode(),
                _count, _max, _samples.size(),
                _units.convert(_nanoPeriod, TimeUnit.NANOSECONDS), _units);
        }
    }
}
