//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client.http;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Pool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectionPoolTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionPoolTest.class);

    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        HttpConfiguration httpConfig = new HttpConfiguration();
        HTTP2CServerConnectionFactory connectionFactory = new HTTP2CServerConnectionFactory(httpConfig);
        connector.setConnectionFactories(List.of(connectionFactory));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void disposeServer() throws Exception
    {
        connector = null;
        if (server != null)
        {
            server.stop();
            server = null;
        }
    }

    @AfterEach
    public void disposeClient() throws Exception
    {
        if (client != null)
        {
            client.stop();
            client = null;
        }
    }

    @Test
    public void testMaxDurationConnectionsWithMultiplexedPool() throws Exception
    {
        final int maxDuration = 200;
        AtomicInteger poolCreateCounter = new AtomicInteger();
        AtomicInteger poolRemoveCounter = new AtomicInteger();
        ConnectionPoolFactory factory = new ConnectionPoolFactory("MaxDurationConnectionsWithMultiplexedPool", destination ->
        {
            MultiplexConnectionPool pool = new MultiplexConnectionPool(destination, Pool.StrategyType.FIRST, 2, false, destination, 10)
            {
                @Override
                protected void onCreated(Connection connection)
                {
                    poolCreateCounter.incrementAndGet();
                }

                @Override
                protected void removed(Connection connection)
                {
                    poolRemoveCounter.incrementAndGet();
                }
            };
            pool.setMaxDuration(maxDuration);
            return pool;
        });

        CountDownLatch[] reqExecutingLatches = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] reqExecutedLatches = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        CountDownLatch[] reqFinishingLatches = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, org.eclipse.jetty.server.Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws ServletException
            {
                int req = Integer.parseInt(target.substring(1));
                try
                {
                    LOG.debug("req {} is executing", req);
                    reqExecutingLatches[req].countDown();
                    Thread.sleep(250);
                    reqExecutedLatches[req].countDown();
                    LOG.debug("req {} executed", req);

                    assertTrue(reqFinishingLatches[req].await(5, TimeUnit.SECONDS));

                    response.getWriter().println("req " + req + " executed");
                    response.getWriter().flush();
                    LOG.debug("req {} successful", req);
                }
                catch (Exception e)
                {
                    throw new ServletException(e);
                }
            }
        });

        ClientConnector clientConnector = new ClientConnector();
        HttpClientTransport transport = new HttpClientTransportOverHTTP2(new HTTP2Client(clientConnector));
        transport.setConnectionPoolFactory(factory.factory);
        client = new HttpClient(transport);
        client.start();

        CountDownLatch[] reqClientDoneLatches = new CountDownLatch[] {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};

        sendRequest(reqClientDoneLatches, 0);
        // wait until handler is executing
        assertTrue(reqExecutingLatches[0].await(5, TimeUnit.SECONDS));
        LOG.debug("req 0 executing");

        sendRequest(reqClientDoneLatches, 1);
        // wait until handler executed sleep
        assertTrue(reqExecutedLatches[1].await(5, TimeUnit.SECONDS));
        LOG.debug("req 1 executed");

        // Now the pool contains one connection that is expired but in use by 2 threads.

        sendRequest(reqClientDoneLatches, 2);
        LOG.debug("req2 sent");
        assertTrue(reqExecutingLatches[2].await(5, TimeUnit.SECONDS));
        LOG.debug("req2 executing");

        // The 3rd request has tried the expired request and marked it as closed as it has expired, then used a 2nd one.

        // release and wait for req2 to be done before releasing req1
        reqFinishingLatches[2].countDown();
        assertTrue(reqClientDoneLatches[2].await(5, TimeUnit.SECONDS));
        reqFinishingLatches[1].countDown();

        // release req0 once req1 is done; req 1 should not have closed the response as req 0 is still running
        assertTrue(reqClientDoneLatches[1].await(5, TimeUnit.SECONDS));
        reqFinishingLatches[0].countDown();
        assertTrue(reqClientDoneLatches[0].await(5, TimeUnit.SECONDS));

        // Check that the pool created 2 and removed 2 connections;
        // 2 were removed b/c waiting for req 2 means the 2nd connection
        // expired and has to be removed and closed upon being returned to the pool.
        assertThat(poolCreateCounter.get(), Matchers.is(2));
        assertThat(poolRemoveCounter.get(), Matchers.is(2));
    }

    private void sendRequest(CountDownLatch[] reqClientDoneLatches, int i)
    {
        client.newRequest("localhost", connector.getLocalPort())
            .path("/" + i)
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertThat("req " + i + " failed", result.getResponse().getStatus(), Matchers.is(200));
                reqClientDoneLatches[i].countDown();
            });
    }

    private static class ConnectionPoolFactory
    {
        private final String name;
        private final ConnectionPool.Factory factory;

        private ConnectionPoolFactory(String name, ConnectionPool.Factory factory)
        {
            this.name = name;
            this.factory = factory;
        }

        @Override
        public String toString()
        {
            return name;
        }
    }
}
