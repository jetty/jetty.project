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

public class HttpDestination implements Destination
{
    private final AtomicInteger connectionCount = new AtomicInteger();
    private final HttpClient client;
    private final String scheme;
    private final String host;
    private final int port;
    private final Queue<Response> requests;
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

        HttpResponse response = new HttpResponse(request, listener);

        if (client.isRunning())
        {
            if (requests.offer(response))
            {
                if (!client.isRunning() && requests.remove(response))
                {
                    throw new RejectedExecutionException(HttpClient.class.getSimpleName() + " is shutting down");
                }
                else
                {
                    Request.Listener requestListener = request.listener();
                    notifyRequestQueued(requestListener, request);
                    ensureConnection();
                }
            }
            else
            {
                throw new RejectedExecutionException("Max requests per address " + client.getMaxQueueSizePerAddress() + " exceeded");
            }
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
            // TODO: log or abort request send ?
        }
    }

    private void ensureConnection()
    {
        int maxConnections = client.getMaxConnectionsPerAddress();
        while (true)
        {
            int count = connectionCount.get();

            if (count >= maxConnections)
                break;

            if (connectionCount.compareAndSet(count, count + 1))
            {
                newConnection(new Callback<Connection>()
                {
                    @Override
                    public void completed(Connection connection)
                    {
                        dispatch(connection);
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
     * Responsibility of this method is to dequeue a request, associate it to the given {@code connection}
     * and dispatch a thread to execute the request.
     *
     * This can be done in several ways: one could be to
     * @param connection
     */
    protected void dispatch(final Connection connection)
    {
        final Response response = requests.poll();
        if (response == null)
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
                    connection.send(response.request(), response.listener());
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
}
