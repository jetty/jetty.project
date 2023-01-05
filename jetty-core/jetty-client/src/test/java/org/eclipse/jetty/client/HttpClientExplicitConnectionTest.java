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

package org.eclipse.jetty.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.internal.HttpDestination;
import org.eclipse.jetty.client.transport.internal.HttpConnectionOverHTTP;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientExplicitConnectionTest extends AbstractHttpClientServerTest
{
    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExplicitConnection(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        Destination destination = client.resolveDestination(request);
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);
            ContentResponse response = listener.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatus());

            HttpDestination httpDestination = (HttpDestination)destination;
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)httpDestination.getConnectionPool();
            assertTrue(connectionPool.getActiveConnections().isEmpty());
            assertTrue(connectionPool.getIdleConnections().isEmpty());
        }
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExplicitConnectionIsClosedOnRemoteClose(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Request request = client.newRequest("localhost", connector.getLocalPort()).scheme(scenario.getScheme());
        Destination destination = client.resolveDestination(request);
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        Connection connection = futureConnection.get(5, TimeUnit.SECONDS);
        FutureResponseListener listener = new FutureResponseListener(request);
        connection.send(request, listener);
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        assertEquals(200, response.getStatus());

        // Wait some time to have the client is an idle state.
        TimeUnit.SECONDS.sleep(1);

        connector.stop();

        // Give the connection some time to process the remote close.
        TimeUnit.SECONDS.sleep(1);

        HttpConnectionOverHTTP httpConnection = (HttpConnectionOverHTTP)connection;
        assertFalse(httpConnection.getEndPoint().isOpen());

        HttpDestination httpDestination = (HttpDestination)destination;
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)httpDestination.getConnectionPool();
        assertTrue(connectionPool.getActiveConnections().isEmpty());
        assertTrue(connectionPool.getIdleConnections().isEmpty());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExplicitConnectionResponseListeners(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        CountDownLatch responseLatch = new CountDownLatch(1);
        Request request = client.newRequest("localhost", connector.getLocalPort())
                .scheme(scenario.getScheme())
                .onResponseSuccess(response -> responseLatch.countDown());
        Destination destination = client.resolveDestination(request);
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        Connection connection = futureConnection.get(5, TimeUnit.SECONDS);

        FutureResponseListener listener = new FutureResponseListener(request);
        connection.send(request, listener);
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }
}
