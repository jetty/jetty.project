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
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.NanoTime;

/**
 * Counts the rate that {@link Long}s are added to this from the time of creation or the last call to {@link #reset()}.
 */
public class RateCounter
{
    private final LongAdder _total = new LongAdder();
    private final AtomicLong _nanoTime = new AtomicLong(NanoTime.now());

    public void add(long l)
    {
        _total.add(l);
    }

    public long getRate()
    {
        long elapsed = NanoTime.millisSince(_nanoTime.get());
        return elapsed == 0 ? 0 : _total.sum() * 1000 / elapsed;
    }

    public void reset()
    {
        _nanoTime.set(NanoTime.now());
        _total.reset();
    }
}
