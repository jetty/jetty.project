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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.RoundRobinConnectionPool;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.server.Request;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RoundRobinConnectionPoolTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testRoundRobin(Transport transport) throws Exception
    {
        init(transport);
        AtomicBoolean record = new AtomicBoolean();
        List<Integer> remotePorts = new CopyOnWriteArrayList<>();
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                if (record.get())
                    remotePorts.add(request.getRemotePort());
            }
        });

        int maxConnections = 3;
        CompletableFuture<Void> setup = new CompletableFuture<>();
        scenario.client.getTransport().setConnectionPoolFactory(destination ->
        {
            RoundRobinConnectionPool pool = new RoundRobinConnectionPool(destination, maxConnections, destination);
            pool.preCreateConnections(maxConnections).handle((r, x) -> x != null ? setup.completeExceptionally(x) : setup.complete(null));
            return pool;
        });

        // Send one request to trigger destination creation
        // and connection pool pre-creation of connections,
        // so we can test reliably the round-robin behavior.
        scenario.client.newRequest(scenario.newURI())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        setup.get(5, TimeUnit.SECONDS);

        record.set(true);
        int requests = 2 * maxConnections - 1;
        for (int i = 0; i < requests; ++i)
        {
            ContentResponse response = scenario.client.newRequest(scenario.newURI())
                .timeout(5, TimeUnit.SECONDS)
                .send();
            assertEquals(HttpStatus.OK_200, response.getStatus());
        }

        assertThat(remotePorts.size(), Matchers.equalTo(requests));
        for (int i = 0; i < requests; ++i)
        {
            int base = i % maxConnections;
            int expected = remotePorts.get(base);
            int candidate = remotePorts.get(i);
            assertThat(scenario.client.dump() + System.lineSeparator() + remotePorts, expected, Matchers.equalTo(candidate));
            if (transport != Transport.UNIX_DOMAIN && i > 0)
                assertThat(remotePorts.get(i - 1), Matchers.not(Matchers.equalTo(candidate)));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testMultiplex(Transport transport) throws Exception
    {
        init(transport);
        int multiplex = 1;
        if (scenario.transport.isMultiplexed())
            multiplex = 4;
        int maxMultiplex = multiplex;

        int maxConnections = 3;
        int count = maxConnections * maxMultiplex;

        AtomicBoolean record = new AtomicBoolean();
        List<Integer> remotePorts = new CopyOnWriteArrayList<>();
        AtomicReference<CountDownLatch> requestLatch = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(count);
        CyclicBarrier barrier = new CyclicBarrier(count + 1);
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                try
                {
                    if (record.get())
                    {
                        remotePorts.add(request.getRemotePort());
                        requestLatch.get().countDown();
                        serverLatch.countDown();
                        barrier.await();
                    }
                }
                catch (Exception x)
                {
                    throw new RuntimeException(x);
                }
            }
        });

        CompletableFuture<Void> setup = new CompletableFuture<>();
        scenario.client.getTransport().setConnectionPoolFactory(destination ->
        {
            RoundRobinConnectionPool pool = new RoundRobinConnectionPool(destination, maxConnections, destination);
            pool.preCreateConnections(maxConnections).handle((r, x) -> x != null ? setup.completeExceptionally(x) : setup.complete(null));
            return pool;
        });

        // Send one request to trigger destination creation
        // and connection pool pre-creation of connections,
        // so we can test reliably the round-robin behavior.
        scenario.client.newRequest(scenario.newURI())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        setup.get(5, TimeUnit.SECONDS);

        record.set(true);
        CountDownLatch clientLatch = new CountDownLatch(count);
        AtomicInteger requests = new AtomicInteger();
        for (int i = 0; i < count; ++i)
        {
            CountDownLatch latch = new CountDownLatch(1);
            requestLatch.set(latch);
            scenario.client.newRequest(scenario.newURI())
                .path("/" + i)
                .onRequestQueued(request -> requests.incrementAndGet())
                .onRequestBegin(request -> requests.decrementAndGet())
                .timeout(5, TimeUnit.SECONDS)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.OK_200)
                        clientLatch.countDown();
                });
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertEquals(0, requests.get());

        barrier.await();

        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        assertThat(remotePorts.toString(), remotePorts.size(), Matchers.equalTo(count));
        for (int i = 0; i < count; ++i)
        {
            int base = i % maxConnections;
            int expected = remotePorts.get(base);
            int candidate = remotePorts.get(i);
            assertThat(scenario.client.dump() + System.lineSeparator() + remotePorts, expected, Matchers.equalTo(candidate));
            if (transport != Transport.UNIX_DOMAIN && i > 0)
                assertThat(remotePorts.get(i - 1), Matchers.not(Matchers.equalTo(candidate)));
        }
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testMultiplexWithMaxUsage(Transport transport) throws Exception
    {
        init(transport);

        int multiplex = 1;
        if (scenario.transport.isMultiplexed())
            multiplex = 2;
        int maxMultiplex = multiplex;

        int maxUsage = 3;
        int maxConnections = 4;
        int count = 2 * maxConnections * maxMultiplex * maxUsage;

        List<Integer> remotePorts = new CopyOnWriteArrayList<>();
        scenario.start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                remotePorts.add(request.getRemotePort());
            }
        });
        if (transport == Transport.H3)
            ((QuicServerConnector)scenario.connector).getQuicConfiguration().setMaxBidirectionalRemoteStreams(maxUsage);
        scenario.client.getTransport().setConnectionPoolFactory(destination ->
        {
            RoundRobinConnectionPool pool = new RoundRobinConnectionPool(destination, maxConnections, destination, maxMultiplex);
            pool.setMaxUsageCount(maxUsage);
            return pool;
        });

        CountDownLatch clientLatch = new CountDownLatch(count);
        for (int i = 0; i < count; ++i)
        {
            scenario.client.newRequest(scenario.newURI())
                .path("/" + i)
                .timeout(5, TimeUnit.SECONDS)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.OK_200)
                        clientLatch.countDown();
                });
        }
        assertTrue(clientLatch.await(count, TimeUnit.SECONDS));
        assertEquals(count, remotePorts.size());

        // Unix Domain does not have ports.
        if (transport == Transport.UNIX_DOMAIN)
            return;

        // UDP does not have TIME_WAIT so ports may be reused by different connections.
        if (transport == Transport.H3)
            return;

        // Maps {remote_port -> number_of_times_port_was_used}.
        Map<Integer, Long> results = remotePorts.stream()
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        // RoundRobinConnectionPool may open more connections than expected.
        // For example with maxUsage=2, requests could be sent to these ports:
        // [p1, p2, p3 | p1, p2, p3 | p4, p4, p5 | p6, p5, p7]
        // Opening p5 and p6 was delayed, so the opening of p7 was triggered
        // to replace p4 while p5 and p6 were busy sending their requests.
        assertThat(results.toString(), count / maxUsage, lessThanOrEqualTo(results.size()));
    }
}
