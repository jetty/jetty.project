//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2;

import java.time.Duration;

import org.eclipse.jetty.io.EndPoint;

/**
 * <p>An implementation of {@link RateControl} that limits the number of
 * events within a time period.</p>
 * <p>Events are kept in a queue and for each event the queue is first
 * drained of the old events outside the time window, and then the new
 * event is added to the queue. The size of the queue is maintained
 * separately in an AtomicInteger and if it exceeds the max
 * number of events then {@link #onEvent(Object)} returns {@code false}.</p>
 *
 * @deprecated use {@link org.eclipse.jetty.io.WindowRateControl} instead.
 */
@Deprecated(since = "12.1.11", forRemoval = true)
public class WindowRateControl extends org.eclipse.jetty.io.WindowRateControl implements RateControl
{
    public static WindowRateControl fromEventsPerSecond(int maxEvents)
    {
        return new WindowRateControl(maxEvents, Duration.ofSeconds(1));
    }

    public WindowRateControl(int maxEvents, Duration window)
    {
        super(maxEvents, window);
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
