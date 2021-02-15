//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.http.HttpConnectionOverHTTP;
import org.eclipse.jetty.client.http.HttpDestinationOverHTTP;
import org.eclipse.jetty.client.util.FutureResponseListener;
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

        Destination destination = client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            Request request = client.newRequest(destination.getHost(), destination.getPort()).scheme(scenario.getScheme());
            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);
            ContentResponse response = listener.get(5, TimeUnit.SECONDS);

            assertNotNull(response);
            assertEquals(200, response.getStatus());

            HttpDestinationOverHTTP httpDestination = (HttpDestinationOverHTTP)destination;
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

        Destination destination = client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        Connection connection = futureConnection.get(5, TimeUnit.SECONDS);
        Request request = client.newRequest(destination.getHost(), destination.getPort()).scheme(scenario.getScheme());
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

        HttpDestinationOverHTTP httpDestination = (HttpDestinationOverHTTP)destination;
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)httpDestination.getConnectionPool();
        assertTrue(connectionPool.getActiveConnections().isEmpty());
        assertTrue(connectionPool.getIdleConnections().isEmpty());
    }

    @ParameterizedTest
    @ArgumentsSource(ScenarioProvider.class)
    public void testExplicitConnectionResponseListeners(Scenario scenario) throws Exception
    {
        start(scenario, new EmptyServerHandler());

        Destination destination = client.getDestination(scenario.getScheme(), "localhost", connector.getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        Connection connection = futureConnection.get(5, TimeUnit.SECONDS);
        CountDownLatch responseLatch = new CountDownLatch(1);
        Request request = client.newRequest(destination.getHost(), destination.getPort())
            .scheme(scenario.getScheme())
            .onResponseSuccess(response -> responseLatch.countDown());

        FutureResponseListener listener = new FutureResponseListener(request);
        connection.send(request, listener);
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }
}
