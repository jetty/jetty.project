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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts the rate that {@link Long}s are added to this from the time of creation or the last call to {@link #reset()}.
 */
public class RateCounter
{
    private final LongAdder _total = new LongAdder();
    private final AtomicLong _timeStamp = new AtomicLong(System.nanoTime());

    public void add(long l)
    {
        _total.add(l);
    }

    public long getRate()
    {
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - _timeStamp.get());
        return elapsed == 0 ? 0 : _total.sum() * 1000 / elapsed;
    }

    public void reset()
    {
        _timeStamp.getAndSet(System.nanoTime());
        _total.reset();
    }
}
