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
