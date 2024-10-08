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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.awaitility.Awaitility;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.both;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DoSHandlerTest
{
    @Test
    public void testTrackerSteadyBelowRate() throws Exception
    {
        DoSHandler handler = new DoSHandler(100);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        for (int sample = 0; sample < 400; sample++)
        {
            boolean exceeded = tracker.onRequest(now);
            assertFalse(exceeded);
            now += TimeUnit.MILLISECONDS.toNanos(11);
        }
        double rate = 0.0;
        if (tracker.getRateControl() instanceof DoSHandler.ExponentialMovingAverageRateControlFactory.RateControl rc)
            rate = rc.getCurrentRatePerSecond();
        if (tracker.getRateControl() instanceof DoSHandler.LeakyBucketRateControlFactory.RateControl rc)
            rate = rc.getCurrentRatePerSecond();


        assertThat(rate, both(greaterThan((1000.0D / 11) - 5)).and(lessThan(100.0D)));
    }

    @Test
    public void testTrackerSteadyAboveRate() throws Exception
    {
        DoSHandler handler = new DoSHandler(100);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        boolean exceeded = false;
        for (int sample = 0; sample < 200; sample++)
        {
            if (tracker.onRequest(now))
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
        DoSHandler handler = new DoSHandler(100);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        for (int sample = 0; sample < 20; sample++)
        {
            for (int burst = 0; burst < 9; burst++)
            {
                boolean exceeded = tracker.onRequest(now);
                assertFalse(exceeded);
            }

            now += TimeUnit.MILLISECONDS.toNanos(100);
        }
    }

    @Test
    public void testTrackerUnevenAboveRate() throws Exception
    {
        DoSHandler handler = new DoSHandler(100);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        boolean exceeded = false;
        for (int sample = 0; sample < 20; sample++)
        {
            for (int burst = 0; burst < 11; burst++)
            {
                if (tracker.onRequest(now))
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
        DoSHandler handler = new DoSHandler(100);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 99; burst++)
            {
                boolean exceeded = tracker.onRequest(now);
                assertFalse(exceeded);
            }

            now += TimeUnit.MILLISECONDS.toNanos(1000);
        }
    }

    @Test
    public void testTrackerBurstAboveRate() throws Exception
    {
        DoSHandler handler = new DoSHandler(100);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        boolean exceeded = false;
        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 101; burst++)
            {
                if (tracker.onRequest(now))
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
        DoSHandler handler = new DoSHandler(100);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 99; burst++)
                assertFalse(tracker.onRequest(now++));

            now += TimeUnit.MILLISECONDS.toNanos(1000) - 100;
        }

        double rate = tracker.getRateControl() instanceof DoSHandler.ExponentialMovingAverageRateControlFactory.RateControl rc ? rc.getCurrentRatePerSecond() : 0.0;
        assertThat(rate, both(greaterThan(90.0D)).and(lessThan(100.0D)));

        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 49; burst++)
                assertFalse(tracker.onRequest(now++));

            now += TimeUnit.MILLISECONDS.toNanos(1000) - 100;
        }

        rate = tracker.getRateControl() instanceof DoSHandler.ExponentialMovingAverageRateControlFactory.RateControl rc ? rc.getCurrentRatePerSecond() : 0.0;
        assertThat(rate, both(greaterThan(40.0D)).and(lessThan(50.0D)));
    }

    @Test
    public void testOKRequestRate() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        DoSHandler dosHandler = new DoSHandler(1000);
        DumpHandler dumpHandler = new DumpHandler();
        server.setHandler(dosHandler);
        dosHandler.setHandler(dumpHandler);

        server.start();

        long now = System.nanoTime();
        long end = now + TimeUnit.SECONDS.toNanos(5);
        CountDownLatch latch = new CountDownLatch(90);
        for (int thread = 0; thread < 90; thread++)
        {
            server.getThreadPool().execute(() ->
            {
                try
                {
                    while (NanoTime.isBefore(NanoTime.now(), end))
                    {
                        String response = connector.getResponse("""
                                GET / HTTP/1.1\r
                                Host: local\r
                                
                                """);
                        assertThat(response, containsString("200 OK"));
                        Thread.sleep(100);
                    }
                    latch.countDown();
                }
                catch (Throwable x)
                {
                    throw new RuntimeException(x);
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testHighRequestRate() throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        DoSHandler dosHandler = new DoSHandler(1000);
        DumpHandler dumpHandler = new DumpHandler();
        server.setHandler(dosHandler);
        dosHandler.setHandler(dumpHandler);

        server.start();

        long now = System.nanoTime();
        long end = now + TimeUnit.SECONDS.toNanos(5);
        AtomicInteger outstanding = new AtomicInteger(0);
        AtomicInteger calm = new AtomicInteger();
        for (int thread = 0; thread < 90; thread++)
        {
            server.getThreadPool().execute(() ->
            {
                try
                {
                    while (NanoTime.isBefore(NanoTime.now(), end))
                    {
                        try
                        {
                            outstanding.incrementAndGet();
                            String response = connector.getResponse("""
                                GET / HTTP/1.1\r
                                Host: local\r
                                
                                """);
                            if (response.contains(" 429 "))
                                calm.incrementAndGet();
                            Thread.sleep(70);
                        }
                        finally
                        {
                            outstanding.decrementAndGet();
                        }
                    }
                }
                catch (Throwable x)
                {
                    throw new RuntimeException(x);
                }
            });
        }

        Awaitility.waitAtMost(10, TimeUnit.SECONDS).until(() -> outstanding.get() == 0);
        assertThat(calm.get(), greaterThan(0));
    }
}
