//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Gives the same basic functionality of {@link LongAdder} but allows you to check
 * the rate of increase of the sum since the last call to {@link #getRate()};
 */
public class RateCounter
{
    private final LongAdder _total = new LongAdder();
    private final LongAdder _totalSinceRateCheck = new LongAdder();
    private final AtomicLong _rateCheckTimeStamp = new AtomicLong(System.nanoTime());

    public long sum()
    {
        return _total.sum();
    }

    public void add(long l)
    {
        _total.add(l);
        _totalSinceRateCheck.add(l);
    }

    public void reset()
    {
        _rateCheckTimeStamp.getAndSet(System.nanoTime());
        _totalSinceRateCheck.reset();
        _total.reset();
    }

    public long getRate()
    {
        long totalSinceLastCheck = _totalSinceRateCheck.sumThenReset();
        long now = System.nanoTime();
        long then = _rateCheckTimeStamp.getAndSet(now);
        long elapsed = TimeUnit.NANOSECONDS.toMillis(now - then);
        return elapsed == 0 ? 0 : totalSinceLastCheck * 1000 / elapsed;
    }
}
