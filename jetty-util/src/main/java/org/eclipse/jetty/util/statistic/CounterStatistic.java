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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * <p>Statistics on a counter value.</p>
 * <p>This class keeps the total, current and maximum value of a counter
 * that can be incremented and decremented. The total refers only to increments.</p>
 */
public class CounterStatistic
{
    private final LongAccumulator _max = new LongAccumulator(Math::max, 0L);
    private final AtomicLong _current = new AtomicLong();
    private final LongAdder _total = new LongAdder();

    /**
     * Resets the max and total to the current value.
     */
    public void reset()
    {
        _total.reset();
        _max.reset();
        long current = _current.get();
        _total.add(current);
        _max.accumulate(current);
    }

    /**
     * Resets the max, total and current value to the given parameter.
     *
     * @param value the new current value
     */
    public void reset(final long value)
    {
        _current.set(value);
        _total.reset();
        _max.reset();
        if (value > 0)
        {
            _total.add(value);
            _max.accumulate(value);
        }
    }

    /**
     * @param delta the amount to add to the counter
     * @return the new counter value
     */
    public long add(final long delta)
    {
        long value = _current.addAndGet(delta);
        if (delta > 0)
        {
            _total.add(delta);
            _max.accumulate(value);
        }
        return value;
    }

    /**
     * Increments the value by one.
     *
     * @return the new counter value after the increment
     */
    public long increment()
    {
        long value = _current.incrementAndGet();
        _total.increment();
        _max.accumulate(value);
        return value;
    }

    /**
     * Decrements the value by one.
     *
     * @return the new counter value after the decrement
     */
    public long decrement()
    {
        return _current.decrementAndGet();
    }

    /**
     * @return max counter value
     */
    public long getMax()
    {
        return _max.get();
    }

    /**
     * @return current counter value
     */
    public long getCurrent()
    {
        return _current.get();
    }

    /**
     * @return total counter value
     */
    public long getTotal()
    {
        return _total.sum();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{c=%d,m=%d,t=%d}", getClass().getSimpleName(), hashCode(), getCurrent(), getMax(), getTotal());
    }
}
