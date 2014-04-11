//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.Atomics;


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
    protected final AtomicLong _max = new AtomicLong();
    protected final AtomicLong _curr = new AtomicLong();
    protected final AtomicLong _total = new AtomicLong();

    /* ------------------------------------------------------------ */
    public void reset()
    {
        reset(0);
    }

    /* ------------------------------------------------------------ */
    public void reset(final long value)
    {
        _max.set(value);
        _curr.set(value);
        _total.set(0); // total always set to 0 to properly calculate cumulative total
    }

    /* ------------------------------------------------------------ */
    /**
     * @param delta the amount to add to the count
     */
    public void add(final long delta)
    {
        long value=_curr.addAndGet(delta);
        if (delta > 0)
            _total.addAndGet(delta);
        Atomics.updateMax(_max,value);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param delta the amount to subtract the count by.
     */
    public void subtract(final long delta)
    {
        add(-delta);
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void increment()
    {
        add(1);
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public void decrement()
    {
        add(-1);
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
        return _curr.get();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return total value
     */
    public long getTotal()
    {
        return _total.get();
    }
}
