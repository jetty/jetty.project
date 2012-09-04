//========================================================================
//Copyright 2012-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.client;

import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpDestination implements Destination
{
    private static final Logger LOG = Log.getLogger(HttpDestination.class);

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final HttpClient client;
    private final String scheme;
    private final String host;
    private final int port;
    private final Queue<RequestPair> requests;
    private final Queue<Connection> idleConnections;
    private final Queue<Connection> activeConnections;

    public HttpDestination(HttpClient client, String scheme, String host, int port)
    {
        this.client = client;
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.requests = new ArrayBlockingQueue<>(client.getMaxQueueSizePerAddress());
        this.idleConnections = new ArrayBlockingQueue<>(client.getMaxConnectionsPerAddress());
        this.activeConnections = new ArrayBlockingQueue<>(client.getMaxConnectionsPerAddress());
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

    @Override
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
                    throw new RejectedExecutionException(HttpClient.class.getSimpleName() + " is stopping");
                }
                else
                {
                    LOG.debug("Queued {}", request);
                    notifyRequestQueued(request.listener(), request);
                    ensureConnection(); // TODO: improve and test this
                }
            }
            else
            {
                throw new RejectedExecutionException("Max requests per address " + client.getMaxQueueSizePerAddress() + " exceeded");
            }
        }
        else
        {
            throw new RejectedExecutionException(HttpClient.class.getSimpleName() + " is stopped");
        }
    }

    private void notifyRequestQueued(Request.Listener listener, Request request)
    {
        try
        {
            if (listener != null)
                listener.onQueued(request);
        }
        catch (Exception x)
        {
            LOG.info("Exception while notifying listener " + listener, x);
        }
    }

    private void ensureConnection()
    {
        final int maxConnections = client.getMaxConnectionsPerAddress();
        while (true)
        {
            int current = connectionCount.get();
            final int next = current + 1;

            if (next > maxConnections)
            {
                LOG.debug("Max connections reached {}: {}", this, current);
                break;
            }

            if (connectionCount.compareAndSet(current, next))
            {
                newConnection(new Callback<Connection>()
                {
                    @Override
                    public void completed(Connection connection)
                    {
                        LOG.debug("Created connection {}/{} for {}", next, maxConnections, this);
                        process(connection);
                    }

                    @Override
                    public void failed(Connection connection, Throwable x)
                    {
                        // TODO: what here ?
                    }
                });
                break;
            }
        }
    }

    public Future<Connection> newConnection()
    {
        FutureCallback<Connection> result = new FutureCallback<>();
        newConnection(result);
        return result;
    }

    private void newConnection(Callback<Connection> callback)
    {
        client.newConnection(this, callback);
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
    protected void process(final Connection connection)
    {
        final RequestPair requestPair = requests.poll();
        if (requestPair == null)
        {
            idleConnections.offer(connection);
        }
        else
        {
            activeConnections.offer(connection);
            client.getExecutor().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    connection.send(requestPair.request, requestPair.listener);
                }
            });
        }
    }

    // TODO: 1. We must do queuing of requests in any case, because we cannot do blocking connect
    // TODO: 2. We must be non-blocking connect, therefore we need to queue

    // Connections should compete for the queue of requests in separate threads
    // that poses a problem of thread pool size: if < maxConnections we're starving
    //
    // conn1 is executed, takes on the queue => I need at least one thread per destination

    // we need to queue the request, pick an idle connection, then execute { conn.send(request, listener) }

    // if I create manually the connection, then I call send(request, listener)

    // Other ways ?


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
