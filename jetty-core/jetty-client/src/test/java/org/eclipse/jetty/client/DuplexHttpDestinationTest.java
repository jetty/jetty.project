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

package org.eclipse.jetty.client;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.transport.HttpDestination;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DuplexHttpDestinationTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAcquireWithEmptyQueue(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", connector.getLocalPort()), false);
        try
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();
            Connection connection = connectionPool.acquire(true);
            if (connection == null)
            {
                // There are no queued requests, so the newly created connection will be idle.
                connection = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
            }
            assertNotNull(connection);
        }
        finally
        {
            destination.stop();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAcquireWithOneExchangeQueued(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", connector.getLocalPort()), false);
        try
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

            // Trigger creation of one connection.
            ConnectionPoolHelper.tryCreate(connectionPool);

            Connection connection = ConnectionPoolHelper.acquire(connectionPool, false);
            if (connection == null)
            {
                // There are no queued requests, so the newly created connection will be idle
                connection = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
            }
            assertNotNull(connection);
        }
        finally
        {
            destination.stop();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSecondAcquireAfterFirstAcquireWithEmptyQueueReturnsSameConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", connector.getLocalPort()), false);
        try
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

            // Trigger creation of one connection.
            ConnectionPoolHelper.tryCreate(connectionPool);

            Connection connection1 = connectionPool.acquire(true);
            if (connection1 == null)
            {
                // There are no queued requests, so the newly created connection will be idle
                connection1 = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
                assertNotNull(connection1);

                Connection connection2 = connectionPool.acquire(true);
                assertSame(connection1, connection2);
            }
        }
        finally
        {
            destination.stop();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testSecondAcquireConcurrentWithFirstAcquireWithEmptyQueueCreatesTwoConnections(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        CountDownLatch idleLatch = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(1);
        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", connector.getLocalPort()), false)
        {
            @Override
            protected ConnectionPool newConnectionPool(HttpClient client)
            {
                return new DuplexConnectionPool(this, client.getMaxConnectionsPerDestination())
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
        try
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

            // Trigger creation of one connection.
            ConnectionPoolHelper.tryCreate(connectionPool);

            // Make sure we entered idleCreated().
            assertTrue(idleLatch.await(5, TimeUnit.SECONDS));

            // There are no available existing connections, so acquire()
            // returns null because we delayed idleCreated() above.
            Connection connection1 = connectionPool.acquire(true);
            assertNull(connection1);

            // Trigger creation of a second connection.
            ConnectionPoolHelper.tryCreate(connectionPool);

            // Second attempt also returns null because we delayed idleCreated() above.
            Connection connection2 = connectionPool.acquire(true);
            assertNull(connection2);

            latch.countDown();

            // There must be 2 idle connections.
            Connection connection = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
            assertNotNull(connection);
            connection = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
            assertNotNull(connection);
        }
        finally
        {
            destination.stop();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testAcquireProcessReleaseAcquireReturnsSameConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", connector.getLocalPort()), false);
        try
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

            // Trigger creation of one connection.
            ConnectionPoolHelper.tryCreate(connectionPool);

            Connection connection1 = connectionPool.acquire(true);
            if (connection1 == null)
            {
                connection1 = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
                assertNotNull(connection1);
                // Acquire the connection to make it active.
                assertSame(connection1, connectionPool.acquire(true), "From idle");
            }

            // There are no exchanges so process() is a no-op.
            Method process = HttpDestination.class.getDeclaredMethod("process", Connection.class);
            process.setAccessible(true);
            process.invoke(destination, connection1);
            destination.release(connection1);

            Connection connection2 = connectionPool.acquire(true);
            assertSame(connection1, connection2, "After release");
        }
        finally
        {
            destination.stop();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testIdleConnectionIdleTimeout(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        long idleTimeout = 1000;
        startClient(scenario, httpClient -> httpClient.setIdleTimeout(idleTimeout));

        HttpDestination destination = new HttpDestination(client, new Origin("http", "localhost", connector.getLocalPort()), false);
        try
        {
            destination.start();
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)destination.getConnectionPool();

            // Trigger creation of one connection.
            ConnectionPoolHelper.tryCreate(connectionPool);

            Connection connection1 = connectionPool.acquire(true);
            if (connection1 == null)
            {
                connection1 = peekIdleConnection(connectionPool, 5, TimeUnit.SECONDS);
                assertNotNull(connection1);

                TimeUnit.MILLISECONDS.sleep(2 * idleTimeout);

                assertFalse(connectionPool.getIdleConnections().iterator().hasNext());
            }
        }
        finally
        {
            destination.stop();
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testRequestFailedIfMaxRequestsQueuedPerDestinationExceeded(Scenario scenario) throws Exception
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
        client.stop();
        client.setDestinationIdleTimeout(1000);
        client.start();

        String host = "localhost";
        int port = connector.getLocalPort();
        Request request = client.newRequest(host, port)
            .scheme(scenario.getScheme())
                .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE));
        Destination destinationBefore = client.resolveDestination(request);
        ContentResponse response = request.send();

        assertEquals(200, response.getStatus());

        Destination destinationAfter = client.resolveDestination(request);
        assertSame(destinationBefore, destinationAfter);

        await().atMost(5, TimeUnit.SECONDS).until(() -> client.getDestinations().isEmpty());

        request = client.newRequest(host, port)
            .scheme(scenario.getScheme())
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE));
        response = request.send();

        assertEquals(200, response.getStatus());

        destinationAfter = client.resolveDestination(request);
        assertNotSame(destinationBefore, destinationAfter);
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testDestinationIsRemovedAfterConnectionError(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());
        client.stop();
        client.setDestinationIdleTimeout(1000);
        client.start();

        String host = "localhost";
        int port = connector.getLocalPort();
        assertTrue(client.getDestinations().isEmpty(), "Destinations of a fresh client must be empty");

        server.stop();
        Request request = client.newRequest(host, port).scheme(scenario.getScheme());
        assertThrows(Exception.class, request::send);

        await().atMost(5, TimeUnit.SECONDS).until(() -> client.getDestinations().isEmpty());
        assertTrue(client.getDestinations().isEmpty(), "Destination must be removed after connection error");
    }

    private Connection peekIdleConnection(DuplexConnectionPool connectionPool, long time, TimeUnit unit)
    {
        return await().atMost(time, unit).until(() ->
        {
            Iterator<Connection> idles = connectionPool.getIdleConnections().iterator();
            return idles.hasNext() ? idles.next() : null;
        }, notNullValue());
    }
}
