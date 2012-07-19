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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Address;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;

public class StandardDestination implements Destination
{
    private final HTTPClient client;
    private final Address address;
    private final Queue<Response> requests;
    private final Queue<Connection> connections;

    public StandardDestination(HTTPClient client, Address address)
    {
        this.client = client;
        this.address = address;
        this.requests = new ArrayBlockingQueue<>(client.getMaxQueueSizePerAddress());
        this.connections = new ArrayBlockingQueue<>(client.getMaxConnectionsPerAddress());
    }

    @Override
    public Address address()
    {
        return address;
    }

    @Override
    public Connection connect(long timeout, TimeUnit unit)
    {
        return null;
    }

    @Override
    public Future<Response> send(Request request, Response.Listener listener)
    {
        if (!address.equals(request.address()))
            throw new IllegalArgumentException("Invalid request address " + request.address() + " for destination " + this);

        StandardResponse response = new StandardResponse(request, listener);

        Connection connection = connections.poll();
        if (connection == null)
            newConnection();

        if (!requests.offer(response))
            throw new RejectedExecutionException("Max requests per address " + client.getMaxQueueSizePerAddress() + " exceeded");

        return response;
    }

    protected Future<Connection> newConnection()
    {
        return client.newConnection(this);
    }

    protected Connection getConnection()
    {
        Connection connection = connections.poll();
        if (connection == null)
        {
            client.
        }
        return connection;
    }

    // TODO: 1. We must do queuing of requests in any case, because we cannot do blocking connect
    // TODO: 2. We must be non-blocking connect, therefore we need to queue

    // Connections should compete for the queue of requests in separate threads
    // that poses a problem of thread pool size: if < maxConnections we're starving
    //

    /**
     * I need a Future<Connection> connect(), and a void connect(Callback<Connection>)
     */
}
