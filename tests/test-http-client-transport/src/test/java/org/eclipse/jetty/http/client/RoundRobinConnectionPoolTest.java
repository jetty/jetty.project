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

package org.eclipse.jetty.http.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.RoundRobinConnectionPool;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Request;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class RoundRobinConnectionPoolTest extends AbstractTest
{
    public RoundRobinConnectionPoolTest(Transport transport)
    {
        super(transport);
    }

    @Test
    public void testRoundRobin() throws Exception
    {
        AtomicBoolean record = new AtomicBoolean();
        List<Integer> remotePorts = new ArrayList<>();
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                if (record.get())
                    remotePorts.add(request.getRemotePort());
            }
        });

        int maxConnections = 3;
        client.getTransport().setConnectionPoolFactory(destination -> new RoundRobinConnectionPool(destination, maxConnections, destination));

        // Prime the connections, so that they are all opened
        // before we actually test the round robin behavior.
        for (int i = 0; i < maxConnections; ++i)
        {
            ContentResponse response = client.newRequest(newURI())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        }

        record.set(true);
        int requests = 2 * maxConnections - 1;
        for (int i = 0; i < requests; ++i)
        {
            ContentResponse response = client.newRequest(newURI())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        }

        Assert.assertThat(remotePorts.size(), Matchers.equalTo(requests));
        for (int i = 0; i < requests; ++i)
        {
            int base = i % maxConnections;
            int expected = remotePorts.get(base);
            int candidate = remotePorts.get(i);
            Assert.assertThat(client.dump() + System.lineSeparator() + remotePorts.toString(), expected, Matchers.equalTo(candidate));
            if (i > 0)
                Assert.assertThat(remotePorts.get(i - 1), Matchers.not(Matchers.equalTo(candidate)));
        }
    }

    @Test
    public void testMultiplex() throws Exception
    {
        int multiplex = 1;
        if (transport == Transport.H2C || transport == Transport.H2)
            multiplex = 4;
        int maxMultiplex = multiplex;

        int maxConnections = 3;
        int count = maxConnections * maxMultiplex;

        AtomicBoolean record = new AtomicBoolean();
        List<Integer> remotePorts = new ArrayList<>();
        AtomicReference<CountDownLatch> requestLatch = new AtomicReference<>();
        CountDownLatch serverLatch = new CountDownLatch(count);
        CyclicBarrier barrier = new CyclicBarrier(count + 1);
        start(new EmptyServerHandler()
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

        client.getTransport().setConnectionPoolFactory(destination -> new RoundRobinConnectionPool(destination, maxConnections, destination, maxMultiplex));

        // Prime the connections, so that they are all opened
        // before we actually test the round robin behavior.
        for (int i = 0; i < maxConnections; ++i)
        {
            ContentResponse response = client.newRequest(newURI())
                    .timeout(5, TimeUnit.SECONDS)
                    .send();
            Assert.assertEquals(HttpStatus.OK_200, response.getStatus());
        }

        record.set(true);
        CountDownLatch clientLatch = new CountDownLatch(count);
        AtomicInteger requests = new AtomicInteger();
        for (int i = 0; i < count; ++i)
        {
            CountDownLatch latch = new CountDownLatch(1);
            requestLatch.set(latch);
            client.newRequest(newURI())
                    .path("/" + i)
                    .onRequestQueued(request -> requests.incrementAndGet())
                    .onRequestBegin(request -> requests.decrementAndGet())
                    .timeout(5, TimeUnit.SECONDS)
                    .send(result ->
                    {
                        if (result.getResponse().getStatus() == HttpStatus.OK_200)
                            clientLatch.countDown();
                    });
            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        Assert.assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, requests.get());

        barrier.await();

        Assert.assertTrue(clientLatch.await(5, TimeUnit.SECONDS));
        Assert.assertThat(remotePorts.size(), Matchers.equalTo(count));
        for (int i = 0; i < count; ++i)
        {
            int base = i % maxConnections;
            int expected = remotePorts.get(base);
            int candidate = remotePorts.get(i);
            Assert.assertThat(client.dump() + System.lineSeparator() + remotePorts.toString(), expected, Matchers.equalTo(candidate));
            if (i > 0)
                Assert.assertThat(remotePorts.get(i - 1), Matchers.not(Matchers.equalTo(candidate)));
        }
    }
}
