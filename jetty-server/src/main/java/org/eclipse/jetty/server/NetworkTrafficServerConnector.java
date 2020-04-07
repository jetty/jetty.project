//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.io.NetworkTrafficSocketChannelEndPoint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A specialized version of {@link ServerConnector} that supports {@link NetworkTrafficListener}s.</p>
 * <p>{@link NetworkTrafficListener}s can be added and removed dynamically before and after this connector has
 * been started without causing {@link java.util.ConcurrentModificationException}s.</p>
 */
public class NetworkTrafficServerConnector extends ServerConnector
{
    private final List<NetworkTrafficListener> listeners = new CopyOnWriteArrayList<>();

    public NetworkTrafficServerConnector(Server server)
    {
        this(server, null, null, null, 0, 0, new HttpConnectionFactory());
    }

    public NetworkTrafficServerConnector(Server server, ConnectionFactory connectionFactory, SslContextFactory sslContextFactory)
    {
        super(server, sslContextFactory, connectionFactory);
    }

    public NetworkTrafficServerConnector(Server server, ConnectionFactory connectionFactory)
    {
        super(server, connectionFactory);
    }

    public NetworkTrafficServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool pool, int acceptors, int selectors, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, pool, acceptors, selectors, factories);
    }

    public NetworkTrafficServerConnector(Server server, SslContextFactory sslContextFactory)
    {
        super(server, sslContextFactory);
    }

    /**
     * @param listener the listener to add
     */
    public void addNetworkTrafficListener(NetworkTrafficListener listener)
    {
        listeners.add(listener);
    }

    /**
     * @param listener the listener to remove
     */
    public void removeNetworkTrafficListener(NetworkTrafficListener listener)
    {
        listeners.remove(listener);
    }

    @Override
    protected ChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
    {
        return new NetworkTrafficSocketChannelEndPoint(channel, selectSet, key, getScheduler(), getIdleTimeout(), listeners);
    }
}
