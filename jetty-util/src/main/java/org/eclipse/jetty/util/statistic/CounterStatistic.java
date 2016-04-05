//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/* ------------------------------------------------------------ */
/** Statistics on a counter value.
 * <p>
 * Keep total, current and maximum values of a counter that
 * can be incremented and decremented. The total refers only
 * to increments.
 *
 */
public class CounterStatistic
{
    protected final LongAccumulator _max = new LongAccumulator(Math::max,0L);
    protected final AtomicLong _current = new AtomicLong();
    protected final LongAdder _total = new LongAdder();

    /* ------------------------------------------------------------ */
    public void reset()
    {
        _total.reset();
        _max.reset();
        long current=_current.get();
        _total.add(current);
        _max.accumulate(current);
    }

    /* ------------------------------------------------------------ */
    public void reset(final long value)
    {
        _current.set(value);
        _total.reset();
        _max.reset();
        if (value>0)
        {
            _total.add(value);
            _max.accumulate(value);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @param delta the amount to add to the count
     * @return the new value
     */
    public long add(final long delta)
    {
        long value=_current.addAndGet(delta);
        if (delta > 0)
        {
            _total.add(delta);
            _max.accumulate(value);
        }
        return value;
    }

    /* ------------------------------------------------------------ */
    /**
     * increment the value by one
     * @return the new value, post increment
     */
    public long increment()
    {
        long value=_current.incrementAndGet();
        _total.increment();
        _max.accumulate(value);
        return value;
    }

    /* ------------------------------------------------------------ */
    /**
     * decrement by 1
     * @return the new value, post-decrement
     */
    public long decrement()
    {
        return _current.decrementAndGet();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return max value
     */
    public long getMax()
    {
        return _max.get();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return current value
     */
    public long getCurrent()
    {
        return _current.get();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return total value
     */
    public long getTotal()
    {
        return _total.sum();
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%x{c=%d,m=%d,t=%d}",this.getClass().getSimpleName(),hashCode(),_current.get(),_max.get(),_total.sum());
    }
}
