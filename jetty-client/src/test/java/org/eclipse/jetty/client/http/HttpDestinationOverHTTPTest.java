//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.http;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.eclipse.jetty.client.AbstractHttpClientServerTest;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.EmptyServerHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

public class HttpDestinationOverHTTPTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_FirstAcquire_WithEmptyQueue(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        try(HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort())))
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
            Connection connection = connectionPool.acquire();
            if (connection == null)
            {
                // There are no queued requests, so the newly created connection will be idle
                connection = pollIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
            }
            assertNotNull(connection);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_SecondAcquire_AfterFirstAcquire_WithEmptyQueue_ReturnsSameConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        try(HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort())))
        {
            destination.start();

            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
            Connection connection1 = connectionPool.acquire();
            if (connection1 == null)
            {
                // There are no queued requests, so the newly created connection will be idle
                connection1 = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
                assertNotNull(connection1);

                Connection connection2 = connectionPool.acquire();
                assertSame(connection1, connection2);
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_SecondAcquire_ConcurrentWithFirstAcquire_WithEmptyQueue_CreatesTwoConnections(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        final CountDownLatch idleLatch = new CountDownLatch(1);
        final CountDownLatch latch = new CountDownLatch(1);
        HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort()))
        {
            @Override
            protected ConnectionPool newConnectionPool(HttpClient client)
            {
                return new DuplexConnectionPool(this, client.getMaxConnectionsPerDestination(), this)
                {
                    @Override
                    protected void onCreated(Connection connection)
                    {
                        try
                        {
                            idleLatch.countDown();
                            latch.await(5, TimeUnit.SECONDS);
                            super.onCreated(connection);
                        }
                        catch (InterruptedException x)
                        {
                            x.printStackTrace();
                        }
                    }
                };
            }
        };
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
            Connection connection1 = connectionPool.acquire();

            // Make sure we entered idleCreated().
            assertTrue(idleLatch.await(5, TimeUnit.SECONDS));

            // There are no available existing connections, so acquire()
            // returns null because we delayed idleCreated() above
            assertNull(connection1);

            // Second attempt also returns null because we delayed idleCreated() above.
            Connection connection2 = connectionPool.acquire();
            assertNull(connection2);

            latch.countDown();

            // There must be 2 idle connections.
            Connection connection = pollIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
            assertNotNull(connection);
            connection = pollIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
            assertNotNull(connection);
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_Acquire_Process_Release_Acquire_ReturnsSameConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        try(HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort())))
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
            Connection connection1 = connectionPool.acquire();
            if (connection1 == null)
            {
                connection1 = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
                assertNotNull(connection1);
                // Acquire the connection to make it active.
                assertSame(connection1, connectionPool.acquire(),"From idle");
            }

            destination.process(connection1);
            destination.release(connection1);

            Connection connection2 = connectionPool.acquire();
            assertSame(connection1, connection2,"After release");
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_IdleConnection_IdleTimeout(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        try (HttpDestinationOverHTTP destination = new HttpDestinationOverHTTP(client, new Origin("http", "localhost", connector.getLocalPort())))
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
            Connection connection1 = connectionPool.acquire();
            if (connection1 == null)
            {
                connection1 = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);

                assertNotNull(connection1);

                TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);

                connection1 = connectionPool.getIdleConnections().poll();
                assertNull(connection1);
            }
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void test_Request_Failed_If_MaxRequestsQueuedPerDestination_Exceeded(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());
        String scheme = scenario.getScheme();
        int maxQueued = 1;
        client.setMaxRequestsQueuedPerDestination(maxQueued);
        client.setMaxConnectionsPerDestination(1);

        // Make one request to open the connection and be sure everything is setup properly
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .send();
        assertEquals(200, response.getStatus());

        // Send another request that is sent immediately
        CountDownLatch successLatch = new CountDownLatch(1);
        CountDownLatch failureLatch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
                .scheme(scheme)
                .path("/one")
                .onRequestQueued(request ->
                {
                    // This request exceeds the maximum queued, should fail
                    client.newRequest("localhost", connector.getLocalPort())
                            .scheme(scheme)
                            .path("/two")
                            .send(result ->
                            {
                                assertTrue(result.isFailed());
                                assertThat(result.getRequestFailure(), Matchers.instanceOf(RejectedExecutionException.class));
                                failureLatch.countDown();
                            });
                })
                .send(result ->
                {
                    if (result.isSucceeded())
                        successLatch.countDown();
                });

        assertTrue(failureLatch.await(5, TimeUnit.SECONDS));
        assertTrue(successLatch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testDestinationIsRemoved(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        Destination destinationBefore = client.getDestination(scenario.getScheme(), host, port);

        ContentResponse response = client.newRequest(host, port)
                .scheme(scenario.getScheme())
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .send();

        assertEquals(200, response.getStatus());

        Destination destinationAfter = client.getDestination(scenario.getScheme(), host, port);
        assertSame(destinationBefore, destinationAfter);

        client.setRemoveIdleDestinations(true);

        response = client.newRequest(host, port)
                .scheme(scenario.getScheme())
                .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
                .send();

        assertEquals(200, response.getStatus());

        destinationAfter = client.getDestination(scenario.getScheme(), host, port);
        assertNotSame(destinationBefore, destinationAfter);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testDestinationIsRemovedAfterConnectionError(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();
        client.setRemoveIdleDestinations(true);
        assertTrue(client.getDestinations().isEmpty(), "Destinations of a fresh client must be empty");

        server.stop();
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        try
        {
            request.send();
            fail("Request to a closed port must fail");
        }
        catch (Exception expected)
        {
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!client.getDestinations().isEmpty() && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertTrue(client.getDestinations().isEmpty(), "Destination must be removed after connection error");
    }

    private Connection pollIdleConnection(DuplexConnectionPool connectionPool, long time, TimeUnit unit) throws InterruptedException
    {
        return await(() -> connectionPool.getIdleConnections().poll(), time, unit);
    }

    private Connection peekIdleConnection(DuplexConnectionPool connectionPool, long time, TimeUnit unit) throws InterruptedException
    {
        return await(() -> connectionPool.getIdleConnections().peek(), time, unit);
    }

    private Connection await(Supplier<Connection> supplier, long time, TimeUnit unit) throws InterruptedException
    {
        long start = System.nanoTime();
        while (unit.toNanos(time) > System.nanoTime() - start)
        {
            Connection connection = supplier.get();
            if (connection != null)
                return connection;
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return null;
    }
}
