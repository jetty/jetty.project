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
import java.util.stream.Stream;

import org.awaitility.Awaitility;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DoSHandlerTest
{
    public static Stream<Arguments> factories()
    {
        return Stream.of(
            Arguments.of(new DoSHandler.LeakingBucketTrackerFactory(100))
        );
    }

    @ParameterizedTest
    @MethodSource("factories")
    public void testTrackerSteadyBelowRate(DoSHandler.Tracker.Factory factory)
    {
        Server server = new Server();
        DoSHandler handler = new DoSHandler(factory);
        server.setHandler(handler);
        LifeCycle.start(server);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime();

        for (int sample = 0; sample < 400; sample++)
        {
            boolean exceeded = !tracker.onRequest(now);
            assertFalse(exceeded);
            now += TimeUnit.MILLISECONDS.toNanos(11);
        }
    }

    @ParameterizedTest
    @MethodSource("factories")
    public void testTrackerSteadyAboveRate(DoSHandler.Tracker.Factory factory)
    {
        Server server = new Server();
        DoSHandler handler = new DoSHandler(factory);
        server.setHandler(handler);
        LifeCycle.start(server);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime();

        boolean exceeded = false;
        for (int sample = 0; sample < 2000; sample++)
        {
            exceeded = !tracker.onRequest(now);
            if (exceeded)
                break;
            now += TimeUnit.MILLISECONDS.toNanos(9);
        }

        assertTrue(exceeded);
    }

    @ParameterizedTest
    @MethodSource("factories")
    public void testTrackerUnevenBelowRate(DoSHandler.Tracker.Factory factory)
    {
        Server server = new Server();
        DoSHandler handler = new DoSHandler(factory);
        server.setHandler(handler);
        LifeCycle.start(server);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime();

        for (int sample = 0; sample < 20; sample++)
        {
            for (int burst = 0; burst < 9; burst++)
            {
                boolean exceeded = !tracker.onRequest(now);
                assertFalse(exceeded);
            }

            now += TimeUnit.MILLISECONDS.toNanos(100);
        }
    }

    @ParameterizedTest
    @MethodSource("factories")
    public void testTrackerUnevenAboveRate(DoSHandler.Tracker.Factory factory)
    {
        Server server = new Server();
        DoSHandler handler = new DoSHandler(factory);
        server.setHandler(handler);
        LifeCycle.start(server);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime();

        boolean exceeded = false;
        loop: for (int sample = 0; sample < 200; sample++)
        {
            for (int burst = 0; burst < 11; burst++)
            {
                exceeded = !tracker.onRequest(now);
                if (exceeded)
                    break loop;
            }

            now += TimeUnit.MILLISECONDS.toNanos(100);
        }

        assertTrue(exceeded);
    }

    @ParameterizedTest
    @MethodSource("factories")
    public void testTrackerBurstBelowRate(DoSHandler.Tracker.Factory factory)
    {
        Server server = new Server();
        DoSHandler handler = new DoSHandler(factory);
        server.setHandler(handler);
        LifeCycle.start(server);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime();

        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 99; burst++)
            {
                boolean exceeded = !tracker.onRequest(now++);
                assertFalse(exceeded);
            }
            now += TimeUnit.MILLISECONDS.toNanos(1000);
        }
    }

    @ParameterizedTest
    @MethodSource("factories")
    public void testTrackerBurstAboveRate(DoSHandler.Tracker.Factory factory)
    {
        Server server = new Server();
        DoSHandler handler = new DoSHandler(factory);
        server.setHandler(handler);
        LifeCycle.start(server);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime();

        boolean exceeded = false;
        for (int seconds = 0; seconds < 2; seconds++)
        {
            for (int burst = 0; burst < 101; burst++)
            {
                if (!tracker.onRequest(now))
                {
                    exceeded = true;
                    break;
                }
            }

            now += TimeUnit.MILLISECONDS.toNanos(1000);
        }

        assertTrue(exceeded);
    }

    @ParameterizedTest
    @MethodSource("factories")
    public void testRecoveryAfterBursts(DoSHandler.Tracker.Factory factory)
    {
        Server server = new Server();
        DoSHandler handler = new DoSHandler(factory);
        server.setHandler(handler);
        LifeCycle.start(server);
        DoSHandler.Tracker tracker = handler.newTracker("id");
        long now = System.nanoTime();

        boolean exceeded = false;
        for (int burst = 0; burst < 1000; burst++)
        {
            now += TimeUnit.MILLISECONDS.toNanos(75);
            exceeded = !tracker.onRequest(now);
        }

        for (int burst = 0; !exceeded && burst < 1000; burst++)
        {
            exceeded = !tracker.onRequest(now++);
        }
        assertTrue(exceeded);

        exceeded = false;
        for (int burst = 0; burst < 1000; burst++)
        {
            now += TimeUnit.MILLISECONDS.toNanos(75);
            exceeded = !tracker.onRequest(now);
        }
        assertFalse(exceeded);
    }

    @ParameterizedTest
    @MethodSource("factories")
    public void testOKRequestRate(DoSHandler.Tracker.Factory factory) throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        DoSHandler dosHandler = new DoSHandler(factory);
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
                        Thread.sleep(1000);
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

    @ParameterizedTest
    @MethodSource("factories")
    public void testHighRequestRate(DoSHandler.Tracker.Factory factory) throws Exception
    {
        Server server = new Server();
        LocalConnector connector = new LocalConnector(server);
        server.addConnector(connector);

        DoSHandler dosHandler = new DoSHandler(factory);
        DumpHandler dumpHandler = new DumpHandler();
        server.setHandler(dosHandler);
        dosHandler.setHandler(dumpHandler);

        server.start();

        long now = System.nanoTime();
        long end = now + TimeUnit.SECONDS.toNanos(5);
        AtomicInteger outstanding = new AtomicInteger(0);
        AtomicInteger calm = new AtomicInteger();
        for (int thread = 0; thread < 120; thread++)
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
                            Thread.sleep(1000);
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
