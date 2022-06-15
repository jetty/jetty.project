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

package org.eclipse.jetty.server;

import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.io.NetworkTrafficSocketChannelEndPoint;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A specialized version of {@link ServerConnector} that supports {@link NetworkTrafficListener}s.</p>
 * <p>A {@link NetworkTrafficListener} can be set and unset dynamically before and after this connector has
 * been started.</p>
 */
public class NetworkTrafficServerConnector extends ServerConnector
{
    private volatile NetworkTrafficListener listener;

    public NetworkTrafficServerConnector(Server server)
    {
        this(server, null, null, null, null, 0, 0, new HttpConnectionFactory());
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

    public NetworkTrafficServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool pool, RetainableByteBufferPool retainablePool, int acceptors, int selectors, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, pool, retainablePool, acceptors, selectors, factories);
    }

    public NetworkTrafficServerConnector(Server server, SslContextFactory.Server sslContextFactory)
    {
        super(server, sslContextFactory);
    }

    /**
     * @param listener the listener to set, or null to unset
     */
    public void setNetworkTrafficListener(NetworkTrafficListener listener)
    {
        this.listener = listener;
    }

    /**
     * @return the listener
     */
    public NetworkTrafficListener getNetworkTrafficListener()
    {
        return listener;
    }

    @Override
    protected SocketChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key)
    {
        return new NetworkTrafficSocketChannelEndPoint(channel, selectSet, key, getScheduler(), getIdleTimeout(), getNetworkTrafficListener());
    }
}
