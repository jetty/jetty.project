//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class IdleTimeoutListenerTest extends AbstractHttpClientServerTest
{
    public IdleTimeoutListenerTest(SslContextFactory sslContextFactory)
    {
        super(sslContextFactory);
    }

    @Test
    public void testIdleTimeoutListenerNotHandling() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();

        long idleTimeout = 500;
        client.setIdleTimeout(idleTimeout);
        // Do not handle the event.
        CountDownLatch latch = new CountDownLatch(1);
        client.addBean((HttpConnection.IdleTimeoutListener)connection ->
        {
            latch.countDown();
            return true;
        });

        // Create a connection with an initial request.
        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait for idle timeout.
        Thread.sleep(2 * idleTimeout);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        HttpDestination destination = client.destinationFor(scheme, host, port);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        Assert.assertEquals(0, connectionPool.getConnectionCount());
    }

    @Test
    public void testIdleTimeoutListenerHandling() throws Exception
    {
        start(new EmptyServerHandler());

        String host = "localhost";
        int port = connector.getLocalPort();

        long idleTimeout = 500;
        client.setIdleTimeout(idleTimeout);
        // Do not handle the event.
        CountDownLatch latch = new CountDownLatch(2);
        client.addBean((HttpConnection.IdleTimeoutListener)connection ->
        {
            latch.countDown();
            if (latch.getCount() > 0)
            {
                // The connection has been activated, release it.
                // If it is not released, the next idle timeout
                // the connection won't be activated (it's already
                // active) and the listener won't be invoked.
                client.destinationFor(scheme, host, port).getConnectionPool().release(connection);
                return false;
            }
            else
            {
                return true;
            }
        });

        // Create a connection with an initial request.
        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        // Wait for 2 full idle timeout periods.
        Thread.sleep(3 * idleTimeout);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        HttpDestination destination = client.destinationFor(scheme, host, port);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        Assert.assertEquals(0, connectionPool.getConnectionCount());
    }

    @Test
    public void testIdleTimeoutListenerSendingRequest() throws Exception
    {
        int pings = 2;
        AtomicInteger serverPings = new AtomicInteger();
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                switch (target)
                {
                    case "/ping":
                        serverPings.incrementAndGet();
                        break;
                    default:
                        break;
                }
            }
        });

        String host = "localhost";
        int port = connector.getLocalPort();

        long idleTimeout = 500;
        client.setIdleTimeout(idleTimeout);
        CountDownLatch clientLatch = new CountDownLatch(pings);
        client.addBean((HttpConnection.IdleTimeoutListener)connection ->
        {
            if (clientLatch.getCount() == 0)
                return true;
            connection.send(client.newRequest(host, port).scheme(scheme).path("/ping"), result ->
            {
                if (result.isSucceeded())
                    clientLatch.countDown();
            });
            return false;
        });

        // Create a connection with an initial request.
        ContentResponse response = client.newRequest(host, port)
                .scheme(scheme)
                .send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());

        Assert.assertTrue(clientLatch.await((pings + 1) * idleTimeout, TimeUnit.MILLISECONDS));
        Assert.assertEquals(pings, serverPings.get());

        // Idle timeout the connection.
        Thread.sleep(2 * idleTimeout);

        HttpDestination destination = client.destinationFor(scheme, host, port);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        Assert.assertEquals(0, connectionPool.getConnectionCount());
    }
}
