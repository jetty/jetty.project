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

package org.eclipse.jetty.client.jmh;

import java.net.URI;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;

import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.DuplexConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpConversation;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexConnectionPool;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.RoundRobinConnectionPool;
import org.eclipse.jetty.client.SendFailure;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.Attachable;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

@State(Scope.Benchmark)
public class ConnectionPoolsBenchmark
{
    private ConnectionPool pool;

    @Param({"round-robin", "cached/multiplex", "uncached/multiplex", "cached/duplex", "uncached/duplex"})
    public static String POOL_TYPE;

    @Setup
    public void setUp() throws Exception
    {
        HttpClient httpClient = new HttpClient()
        {
            @Override
            protected void newConnection(HttpDestination destination, Promise<Connection> promise)
            {
                promise.succeeded(new MockConnection());
            }
        };
        HttpDestination httpDestination = new HttpDestination(httpClient, new Origin("http", "localhost", 8080))
        {
            @Override
            protected SendFailure send(Connection connection, HttpExchange exchange)
            {
                return null;
            }
        };

        HttpConversation httpConversation = new HttpConversation();
        HttpRequest httpRequest = new HttpRequest(httpClient, httpConversation, new URI("http://localhost:8080")) {};
        HttpExchange httpExchange = new HttpExchange(httpDestination, httpRequest, new ArrayList<>());
        httpDestination.getHttpExchanges().add(httpExchange);

        int initialConnections = 12;
        int maxConnections = 100;
        switch (POOL_TYPE)
        {
            case "uncached/duplex":
                pool = new DuplexConnectionPool(httpDestination, maxConnections, false, Callback.NOOP);
                pool.preCreateConnections(initialConnections).get();
                break;
            case "cached/duplex":
                pool = new DuplexConnectionPool(httpDestination, maxConnections, true, Callback.NOOP);
                pool.preCreateConnections(initialConnections).get();
                break;
            case "uncached/multiplex":
                pool = new MultiplexConnectionPool(httpDestination, maxConnections, false, Callback.NOOP, 12);
                pool.preCreateConnections(initialConnections).get();
                break;
            case "cached/multiplex":
                pool = new MultiplexConnectionPool(httpDestination, maxConnections, true, Callback.NOOP, 12);
                pool.preCreateConnections(initialConnections).get();
                break;
            case "round-robin":
                pool = new RoundRobinConnectionPool(httpDestination, maxConnections, Callback.NOOP);
                pool.preCreateConnections(maxConnections).get();
                break;
            default:
                throw new AssertionError("Unknown pool type: " + POOL_TYPE);
        }
    }

    @TearDown
    public void tearDown()
    {
        pool.close();
        pool = null;
    }

    @Benchmark
    public void testPool()
    {
        Connection connection = pool.acquire();
        if (connection == null && !POOL_TYPE.equals("round-robin"))
            throw new AssertionError("from thread " + Thread.currentThread().getName());
        Blackhole.consumeCPU(ThreadLocalRandom.current().nextInt(10, 20));
        if (connection != null)
            pool.release(connection);
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(ConnectionPoolsBenchmark.class.getSimpleName())
            .warmupIterations(3)
            .measurementIterations(3)
            .forks(1)
            .threads(12)
            //.addProfiler(LinuxPerfProfiler.class)
            .build();

        new Runner(opt).run();
    }

    static class MockConnection implements Connection, Attachable
    {
        private Object attachment;

        @Override
        public void close()
        {
        }

        @Override
        public boolean isClosed()
        {
            return false;
        }

        @Override
        public void send(Request request, Response.CompleteListener listener)
        {
        }

        @Override
        public void setAttachment(Object obj)
        {
            this.attachment = obj;
        }

        @Override
        public Object getAttachment()
        {
            return attachment;
        }
    }
}
