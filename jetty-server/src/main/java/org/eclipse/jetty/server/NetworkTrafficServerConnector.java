//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.io.NetworkTrafficSelectChannelEndPoint;
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

    public NetworkTrafficServerConnector(Server server, ConnectionFactory connectionFactory, SslContextFactory.Server sslContextFactory)
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

    public NetworkTrafficServerConnector(Server server, SslContextFactory.Server sslContextFactory)
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
    protected ChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
    {
        NetworkTrafficSelectChannelEndPoint endPoint = new NetworkTrafficSelectChannelEndPoint(channel, selectSet, key, getScheduler(), getIdleTimeout(), listeners);
        return endPoint;
    }
}
