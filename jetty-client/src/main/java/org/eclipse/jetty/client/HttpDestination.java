//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpDestination implements Destination, AutoCloseable, Dumpable
{
    private static final Logger LOG = Log.getLogger(HttpDestination.class);

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final ResponseNotifier responseNotifier = new ResponseNotifier();
    private final HttpClient client;
    private final String scheme;
    private final String host;
    private final int port;
    private final Queue<RequestPair> requests;
    private final BlockingQueue<Connection> idleConnections;
    private final BlockingQueue<Connection> activeConnections;
    private final RequestNotifier requestNotifier;

    public HttpDestination(HttpClient client, String scheme, String host, int port)
    {
        this.client = client;
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.requests = new ArrayBlockingQueue<>(client.getMaxQueueSizePerAddress());
        this.idleConnections = new ArrayBlockingQueue<>(client.getMaxConnectionsPerAddress());
        this.activeConnections = new ArrayBlockingQueue<>(client.getMaxConnectionsPerAddress());
        this.requestNotifier = new RequestNotifier(client);
    }

    protected BlockingQueue<Connection> getIdleConnections()
    {
        return idleConnections;
    }

    protected BlockingQueue<Connection> getActiveConnections()
    {
        return activeConnections;
    }

    @Override
    public String scheme()
    {
        return scheme;
    }

    @Override
    public String host()
    {
        return host;
    }

    @Override
    public int port()
    {
        return port;
    }

    public void send(Request request, Response.Listener listener)
    {
        if (!scheme.equals(request.scheme()))
            throw new IllegalArgumentException("Invalid request scheme " + request.scheme() + " for destination " + this);
        if (!host.equals(request.host()))
            throw new IllegalArgumentException("Invalid request host " + request.host() + " for destination " + this);
        if (port != request.port())
            throw new IllegalArgumentException("Invalid request port " + request.port() + " for destination " + this);

        RequestPair requestPair = new RequestPair(request, listener);
        if (client.isRunning())
        {
            if (requests.offer(requestPair))
            {
                if (!client.isRunning() && requests.remove(requestPair))
                {
                    throw new RejectedExecutionException(client + " is stopping");
                }
                else
                {
                    LOG.debug("Queued {}", request);
                    requestNotifier.notifyQueued(request);
                    Connection connection = acquire();
                    if (connection != null)
                        process(connection, false);
                }
            }
            else
            {
                throw new RejectedExecutionException("Max requests per address " + client.getMaxQueueSizePerAddress() + " exceeded");
            }
        }
        else
        {
            throw new RejectedExecutionException(client + " is stopped");
        }
    }

    public Future<Connection> newConnection()
    {
        FutureCallback<Connection> result = new FutureCallback<>();
        newConnection(result);
        return result;
    }

    protected void newConnection(Callback<Connection> callback)
    {
        client.newConnection(this, callback);
    }

    protected Connection acquire()
    {
        Connection result = idleConnections.poll();
        if (result != null)
            return result;

        final int maxConnections = client.getMaxConnectionsPerAddress();
        while (true)
        {
            int current = connectionCount.get();
            final int next = current + 1;

            if (next > maxConnections)
            {
                LOG.debug("Max connections {} reached for {}", current, this);
                // Try again the idle connections
                return idleConnections.poll();
            }

            if (connectionCount.compareAndSet(current, next))
            {
                LOG.debug("Creating connection {}/{} for {}", next, maxConnections, this);
                newConnection(new Callback<Connection>()
                {
                    @Override
                    public void completed(Connection connection)
                    {
                        LOG.debug("Created connection {}/{} {} for {}", next, maxConnections, connection, HttpDestination.this);
                        process(connection, true);
                    }

                    @Override
                    public void failed(Connection connection, final Throwable x)
                    {
                        LOG.debug("Connection failed {} for {}", x, HttpDestination.this);
                        connectionCount.decrementAndGet();
                        client.getExecutor().execute(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                RequestPair pair = requests.poll();
                                if (pair != null)
                                {
                                    requestNotifier.notifyFailure(pair.request, x);
                                    responseNotifier.notifyComplete(pair.listener, new Result(pair.request, x, null));
                                }
                            }
                        });
                    }
                });
                // Try again the idle connections
                return idleConnections.poll();
            }
        }
    }

    /**
     * <p>Processes a new connection making it idle or active depending on whether requests are waiting to be sent.</p>
     * <p>A new connection is created when a request needs to be executed; it is possible that the request that
     * triggered the request creation is executed by another connection that was just released, so the new connection
     * may become idle.</p>
     * <p>If a request is waiting to be executed, it will be dequeued and executed by the new connection.</p>
     *
     * @param connection the new connection
     */
    protected void process(final Connection connection, boolean dispatch)
    {
        final RequestPair requestPair = requests.poll();
        if (requestPair == null)
        {
            LOG.debug("{} idle", connection);
            if (!idleConnections.offer(connection))
            {
                LOG.debug("{} idle overflow");
                connection.close();
            }
            if (!client.isRunning())
            {
                LOG.debug("{} is stopping", client);
                remove(connection);
                connection.close();
            }
        }
        else
        {
            LOG.debug("{} active", connection);
            if (!activeConnections.offer(connection))
            {
                LOG.warn("{} active overflow");
            }
            if (dispatch)
            {
                client.getExecutor().execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        connection.send(requestPair.request, requestPair.listener);
                    }
                });
            }
            else
            {
                connection.send(requestPair.request, requestPair.listener);
            }
        }
    }

    public void release(Connection connection)
    {
        LOG.debug("{} released", connection);
        if (client.isRunning())
        {
            boolean removed = activeConnections.remove(connection);
            if (removed)
                process(connection, false);
            else
                LOG.debug("{} explicit", connection);
        }
        else
        {
            LOG.debug("{} is stopped", client);
            remove(connection);
            connection.close();
        }
    }

    public void remove(Connection connection)
    {
        LOG.debug("{} removed", connection);
        connectionCount.decrementAndGet();
        activeConnections.remove(connection);
        idleConnections.remove(connection);

        // We need to executed queued requests even if this connection failed.
        // We may create a connection that is not needed, but it will eventually
        // idle timeout, so no worries
        if (!requests.isEmpty())
        {
            connection = acquire();
            if (connection != null)
                process(connection, false);
        }
    }

    public void close()
    {
        for (Connection connection : idleConnections)
            connection.close();
        idleConnections.clear();

        // A bit drastic, but we cannot wait for all requests to complete
        for (Connection connection : activeConnections)
            connection.close();
        activeConnections.clear();

        AsynchronousCloseException failure = new AsynchronousCloseException();
        RequestPair pair;
        while ((pair = requests.poll()) != null)
        {
            requestNotifier.notifyFailure(pair.request, failure);
            responseNotifier.notifyComplete(pair.listener, new Result(pair.request, failure, null));
        }

        connectionCount.set(0);

        LOG.debug("Closed {}", this);
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out, this + " - requests queued: " + requests.size());
        List<String> connections = new ArrayList<>();
        for (Connection connection : idleConnections)
            connections.add(connection + " - IDLE");
        for (Connection connection : activeConnections)
            connections.add(connection + " - ACTIVE");
        ContainerLifeCycle.dump(out, indent, connections);
    }

    @Override
    public String toString()
    {
        return String.format("%s(%s://%s:%d)", HttpDestination.class.getSimpleName(), scheme(), host(), port());
    }

    private static class RequestPair
    {
        private final Request request;
        private final Response.Listener listener;

        public RequestPair(Request request, Response.Listener listener)
        {
            this.request = request;
            this.listener = listener;
        }
    }
}
