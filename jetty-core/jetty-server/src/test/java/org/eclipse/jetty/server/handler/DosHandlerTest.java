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

package org.eclipse.jetty.server.handler;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DosHandlerTest
{
    @BeforeEach
    public void beforeEach() throws Exception
    {
    }

    @AfterEach
    public void afterEach() throws Exception
    {
    }

    @Test
    public void testTrackerSteadyBelowRate() throws Exception
    {
        DosHandler handler = new DosHandler(100);
        DosHandler.Tracker tracker = handler.testTracker();
        long now = tracker.getSampleStart() + TimeUnit.SECONDS.toNanos(10);

        for (int sample = 0; sample < 400; sample++)
        {
            boolean exceeded = tracker.addSampleAndCheckRateExceeded(now);
            assertFalse(exceeded);
            now += TimeUnit.MILLISECONDS.toNanos(11);
        }
        int rate = tracker.getCurrentRatePerSecond(now);
        assertThat(rate, both(greaterThan((1000 / 11) - 5)).and(lessThan(100)));
    }

    @Test
    public void testTrackerSteadyAboveRate() throws Exception
    {
        DosHandler handler = new DosHandler(100);
        DosHandler.Tracker tracker = handler.testTracker();
        long now = tracker.getSampleStart() + TimeUnit.SECONDS.toNanos(10);

        boolean exceeded = false;
        for (int sample = 0; sample < 200; sample++)
        {
            if (tracker.addSampleAndCheckRateExceeded(now))
            {
                exceeded = true;
                break;
            }
            now += TimeUnit.MILLISECONDS.toNanos(9);
        }

        assertTrue(exceeded);
    }

    @Test
    public void testTrackerUnevenBelowRate() throws Exception
    {
        DosHandler handler = new DosHandler(100);
        DosHandler.Tracker tracker = handler.testTracker();
        long now = tracker.getSampleStart() + TimeUnit.SECONDS.toNanos(10);

        for (int sample = 0; sample < 20; sample++)
        {
            for (int burst = 0; burst < 9; burst++)
            {
                boolean exceeded = tracker.addSampleAndCheckRateExceeded(now);
                assertFalse(exceeded);
            }

            now += TimeUnit.MILLISECONDS.toNanos(100);
        }
    }

    @Test
    public void testTrackerUnevenAboveRate() throws Exception
    {
        DosHandler handler = new DosHandler(100);
        DosHandler.Tracker tracker = handler.testTracker();
        long now = tracker.getSampleStart() + TimeUnit.SECONDS.toNanos(10);

        boolean exceeded = false;
        for (int sample = 0; sample < 20; sample++)
        {
            for (int burst = 0; burst < 11; burst++)
            {
                if (tracker.addSampleAndCheckRateExceeded(now))
                {
                    exceeded = true;
                    break;
                }
            }

            now += TimeUnit.MILLISECONDS.toNanos(100);
        }

        assertTrue(exceeded);
    }

    @Test
    public void testTrackerBurstBelowRate() throws Exception
    {
        DosHandler handler = new DosHandler(100);
        DosHandler.Tracker tracker = handler.testTracker();
        long now = tracker.getSampleStart() + TimeUnit.SECONDS.toNanos(10);

        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 99; burst++)
            {
                boolean exceeded = tracker.addSampleAndCheckRateExceeded(now);
                assertFalse(exceeded);
            }

            now += TimeUnit.MILLISECONDS.toNanos(1000);
        }
    }

    @Test
    public void testTrackerBurstAboveRate() throws Exception
    {
        DosHandler handler = new DosHandler(100);
        DosHandler.Tracker tracker = handler.testTracker();
        long now = tracker.getSampleStart() + TimeUnit.SECONDS.toNanos(10);

        boolean exceeded = false;
        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 101; burst++)
            {
                if (tracker.addSampleAndCheckRateExceeded(now))
                {
                    exceeded = true;
                    break;
                }
            }

            now += TimeUnit.MILLISECONDS.toNanos(1000);
        }

        assertTrue(exceeded);
    }

    @Test
    public void testRecoveryAfterBursts() throws Exception
    {
        DosHandler handler = new DosHandler(100);
        DosHandler.Tracker tracker = handler.testTracker();
        long now = tracker.getSampleStart() + TimeUnit.SECONDS.toNanos(10);

        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 99; burst++)
                assertFalse(tracker.addSampleAndCheckRateExceeded(now++));

            now += TimeUnit.MILLISECONDS.toNanos(1000) - 100;
        }

        int rate = tracker.getCurrentRatePerSecond(now);
        assertThat(rate, both(greaterThan(90)).and(lessThan(100)));

        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 49; burst++)
                assertFalse(tracker.addSampleAndCheckRateExceeded(now++));

            now += TimeUnit.MILLISECONDS.toNanos(1000) - 100;
        }

        rate = tracker.getCurrentRatePerSecond(now);
        assertThat(rate, both(greaterThan(40)).and(lessThan(50)));
    }
}
