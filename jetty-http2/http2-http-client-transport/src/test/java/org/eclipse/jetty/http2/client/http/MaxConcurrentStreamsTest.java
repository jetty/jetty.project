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

package org.eclipse.jetty.http2.client.http;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Assert;
import org.junit.Test;

public class MaxConcurrentStreamsTest extends AbstractTest
{
    private void start(int maxConcurrentStreams, Handler handler) throws Exception
    {
        HTTP2ServerConnectionFactory http2 = new HTTP2ServerConnectionFactory(new HttpConfiguration());
        http2.setMaxConcurrentStreams(maxConcurrentStreams);
        prepareServer(http2);
        server.setHandler(handler);
        server.start();
        prepareClient();
        client.start();
    }

    @Test
    public void testOneConcurrentStream() throws Exception
    {
        long sleep = 1000;
        start(1, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                // Sleep a bit to allow the second request to be queued.
                sleep(sleep);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        primeConnection();

        CountDownLatch latch = new CountDownLatch(2);

        // First request is sent immediately.
        client.newRequest("localhost", connector.getLocalPort())
                .path("/first")
                .send(result ->
                {
                    if (result.isSucceeded())
                        latch.countDown();
                });

        // Second request is queued.
        client.newRequest("localhost", connector.getLocalPort())
                .path("/second")
                .send(result ->
                {
                    if (result.isSucceeded())
                        latch.countDown();
                });

        // When the first request returns, the second must be sent.
        Assert.assertTrue(latch.await(5 * sleep, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testTwoConcurrentStreamsThirdWaits() throws Exception
    {
        int maxStreams = 2;
        long sleep = 1000;
        start(maxStreams, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                sleep(sleep);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        primeConnection();

        // Send requests up to the max allowed.
        for (int i = 0; i < maxStreams; ++i)
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .path("/" + i)
                    .send(null);
        }

        // Send the request in excess.
        CountDownLatch latch = new CountDownLatch(1);
        String path = "/excess";
        client.newRequest("localhost", connector.getLocalPort())
                .path(path)
                .send(result ->
                {
                    if (result.getResponse().getStatus() == HttpStatus.OK_200)
                        latch.countDown();
                });

        // The last exchange should remain in the queue.
        HttpDestinationOverHTTP2 destination = (HttpDestinationOverHTTP2)client.getDestination("http", "localhost", connector.getLocalPort());
        Assert.assertEquals(1, destination.getHttpExchanges().size());
        Assert.assertEquals(path, destination.getHttpExchanges().peek().getRequest().getPath());

        Assert.assertTrue(latch.await(5 * sleep, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testAbortedWhileQueued() throws Exception
    {
        long sleep = 1000;
        start(1, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                sleep(sleep);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        primeConnection();

        // Send a request that is aborted while queued.
        client.newRequest("localhost", connector.getLocalPort())
                .path("/aborted")
                .onRequestQueued(request -> request.abort(new Exception()))
                .send(null);

        // Must be able to send another request.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort()).path("/check").send();

        Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testMultipleRequestsQueuedOnConnect() throws Exception
    {
        int maxConcurrent = 10;
        long sleep = 500;
        start(maxConcurrent, new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                sleep(sleep);
            }
        });
        client.setMaxConnectionsPerDestination(1);

        // The first request will open the connection, the others will be queued.
        CountDownLatch latch = new CountDownLatch(maxConcurrent);
        for (int i = 0; i < maxConcurrent; ++i)
        {
            client.newRequest("localhost", connector.getLocalPort())
                    .path("/" + i)
                    .send(result -> latch.countDown());
        }

        // The requests should be processed in parallel, not sequentially.
        Assert.assertTrue(latch.await(maxConcurrent * sleep / 2, TimeUnit.MILLISECONDS));
    }

    private void primeConnection() throws Exception
    {
        // Prime the connection so that the maxConcurrentStream setting arrives to the client.
        client.newRequest("localhost", connector.getLocalPort()).path("/prime").send();
        // Wait for the server to clean up and remove the stream that primes the connection.
        sleep(1000);
    }

    private void sleep(long time)
    {
        try
        {
            Thread.sleep(time);
        }
        catch (InterruptedException x)
        {
            throw new RuntimeException(x);
        }
    }
}
