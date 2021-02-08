//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.parser;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.EndPoint;

/**
 * <p>An implementation of {@link RateControl} that limits the number of
 * events within a time period.</p>
 * <p>Events are kept in a queue and for each event the queue is first
 * drained of the old events outside the time window, and then the new
 * event is added to the queue. The size of the queue is maintained
 * separately in an AtomicInteger and if it exceeds the max
 * number of events then {@link #onEvent(Object)} returns {@code false}.</p>
 */
public class WindowRateControl implements RateControl
{
    private final Queue<Long> events = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int maxEvents;
    private final long window;

    public static WindowRateControl fromEventsPerSecond(int maxEvents)
    {
        return new WindowRateControl(maxEvents, Duration.ofSeconds(1));
    }

    public WindowRateControl(int maxEvents, Duration window)
    {
        this.maxEvents = maxEvents;
        this.window = window.toNanos();
        if (this.window == 0)
            throw new IllegalArgumentException("Invalid duration " + window);
    }

    public int getEventsPerSecond()
    {
        try
        {
            long rate = maxEvents * 1_000_000_000L / window;
            return Math.toIntExact(rate);
        }
        catch (ArithmeticException x)
        {
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public boolean onEvent(Object event)
    {
        long now = System.nanoTime();
        while (true)
        {
            Long time = events.peek();
            if (time == null)
                break;
            if (now < time)
                break;
            if (events.remove(time))
                size.decrementAndGet();
        }
        events.add(now + window);
        return size.incrementAndGet() <= maxEvents;
    }

    public static class Factory implements RateControl.Factory
    {
        private final int maxEventRate;

        public Factory(int maxEventRate)
        {
            this.maxEventRate = maxEventRate;
        }

        @Override
        public RateControl newRateControl(EndPoint endPoint)
        {
            return WindowRateControl.fromEventsPerSecond(maxEventRate);
        }
    }
}
