//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class HttpClientExplicitConnectionTest extends AbstractHttpClientServerTest
{
    public HttpClientExplicitConnectionTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testExplicitConnection() throws Exception
    {
        start(new EmptyServerHandler());

        Destination destination = client.getDestination(scheme, "localhost", connector.getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        try (Connection connection = futureConnection.get(5, TimeUnit.SECONDS))
        {
            Request request = client.newRequest(destination.getHost(), destination.getPort()).scheme(scheme);
            FutureResponseListener listener = new FutureResponseListener(request);
            connection.send(request, listener);
            ContentResponse response = listener.get(5, TimeUnit.SECONDS);

            Assert.assertNotNull(response);
            Assert.assertEquals(200, response.getStatus());

            HttpDestinationOverHTTP httpDestination = (HttpDestinationOverHTTP)destination;
            DuplexConnectionPool connectionPool = (DuplexConnectionPool)httpDestination.getConnectionPool();
            Assert.assertTrue(connectionPool.getActiveConnections().isEmpty());
            Assert.assertTrue(connectionPool.getIdleConnections().isEmpty());
        }
    }

    @Test
    public void testExplicitConnectionIsClosedOnRemoteClose() throws Exception
    {
        start(new EmptyServerHandler());

        Destination destination = client.getDestination(scheme, "localhost", connector.getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        Connection connection = futureConnection.get(5, TimeUnit.SECONDS);
        Request request = client.newRequest(destination.getHost(), destination.getPort()).scheme(scheme);
        FutureResponseListener listener = new FutureResponseListener(request);
        connection.send(request, listener);
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        Assert.assertEquals(200, response.getStatus());

        // Wait some time to have the client is an idle state.
        TimeUnit.SECONDS.sleep(1);

        connector.stop();

        // Give the connection some time to process the remote close.
        TimeUnit.SECONDS.sleep(1);

        HttpConnectionOverHTTP httpConnection = (HttpConnectionOverHTTP)connection;
        Assert.assertFalse(httpConnection.getEndPoint().isOpen());

        HttpDestinationOverHTTP httpDestination = (HttpDestinationOverHTTP)destination;
        DuplexConnectionPool connectionPool = (DuplexConnectionPool)httpDestination.getConnectionPool();
        Assert.assertTrue(connectionPool.getActiveConnections().isEmpty());
        Assert.assertTrue(connectionPool.getIdleConnections().isEmpty());
    }

    @Test
    public void testExplicitConnectionResponseListeners() throws Exception
    {
        start(new EmptyServerHandler());

        Destination destination = client.getDestination(scheme, "localhost", connector.getLocalPort());
        FuturePromise<Connection> futureConnection = new FuturePromise<>();
        destination.newConnection(futureConnection);
        Connection connection = futureConnection.get(5, TimeUnit.SECONDS);
        CountDownLatch responseLatch = new CountDownLatch(1);
        Request request = client.newRequest(destination.getHost(), destination.getPort())
                .scheme(scheme)
                .onResponseSuccess(response -> responseLatch.countDown());

        FutureResponseListener listener = new FutureResponseListener(request);
        connection.send(request, listener);
        ContentResponse response = listener.get(5, TimeUnit.SECONDS);

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        Assert.assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }
}
