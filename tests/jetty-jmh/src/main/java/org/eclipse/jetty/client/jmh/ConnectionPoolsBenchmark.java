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

package org.eclipse.jetty.client.jmh;

import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class ConnectionPoolsBenchmark
{
    /** TODO
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
        HttpDestination httpDestination = new HttpDestination(httpClient, new Origin("http", "localhost", 8080), false)
        {
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
        Connection connection = pool.acquire(true);
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
        **/
}
