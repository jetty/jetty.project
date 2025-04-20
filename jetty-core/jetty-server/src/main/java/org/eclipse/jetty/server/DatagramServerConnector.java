//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.EventListener;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.DatagramChannelEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.Scheduler;

public class DatagramServerConnector extends AbstractNetworkConnector
{
    private final ServerDatagramSelectorManager selectorManager;
    private volatile DatagramChannel datagramChannel;
    private volatile int localPort = -1;

    public DatagramServerConnector(Server server, ConnectionFactory... factories)
    {
        this(server, null, null, null, factories);
    }

    public DatagramServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool bufferPool, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, 0, factories);
        this.selectorManager = new ServerDatagramSelectorManager(getExecutor(), getScheduler(), 1);
    }

    @Override
    public int getLocalPort()
    {
        return localPort;
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = datagramChannel;
        return channel != null && channel.isOpen();
    }

    @Override
    protected void doStart() throws Exception
    {
        addBean(selectorManager);
        addBean(datagramChannel);

        for (EventListener l : getBeans(SelectorManager.SelectorManagerListener.class))
            selectorManager.addEventListener(l);

        super.doStart();

        selectorManager.accept(datagramChannel);
    }

    @Override
    public void open() throws IOException
    {
        if (datagramChannel == null)
        {
            datagramChannel = openDatagramChannel();
            datagramChannel.configureBlocking(false);
            localPort = datagramChannel.socket().getLocalPort();
            if (localPort <= 0)
                throw new IOException("DatagramChannel not bound");
            super.open();
        }
    }

    protected DatagramChannel openDatagramChannel() throws IOException
    {
        InetSocketAddress bindAddress = getHost() == null ? new InetSocketAddress(getPort()) : new InetSocketAddress(getHost(), getPort());
        DatagramChannel datagramChannel = DatagramChannel.open();
        try
        {
            datagramChannel.bind(bindAddress);
            return datagramChannel;
        }
        catch (Throwable e)
        {
            IO.close(datagramChannel);
            throw new IOException("Failed to bind to " + bindAddress, e);
        }
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        super.setIdleTimeout(idleTimeout);
        selectorManager.setIdleTimeout(idleTimeout);
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();

        removeBean(datagramChannel);
        datagramChannel = null;

        for (EventListener l : getBeans(EventListener.class))
        {
            selectorManager.removeEventListener(l);
        }
    }

    @Override
    public void close()
    {
        super.close();
        localPort = -2;
        // Do nothing more here, as we want the DatagramChannel
        // to be closed by the SelectorManager when the
        // SelectorManager is stopped (as a bean) in doStop().
    }

    @Override
    public Object getTransport()
    {
        return datagramChannel;
    }

    @Override
    protected void accept(int acceptorID)
    {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no accept mechanism");
    }

    protected EndPoint newEndPoint(DatagramChannel channel, ManagedSelector selector, SelectionKey selectionKey)
    {
        return new DatagramChannelEndPoint(channel, selector, selectionKey, getScheduler());
    }

    protected Connection newConnection(EndPoint endpoint)
    {
        return getDefaultConnectionFactory().newConnection(DatagramServerConnector.this, endpoint);
    }

    private class ServerDatagramSelectorManager extends SelectorManager
    {
        protected ServerDatagramSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
        {
            EndPoint endPoint = DatagramServerConnector.this.newEndPoint((DatagramChannel)channel, selector, selectionKey);
            endPoint.setIdleTimeout(getIdleTimeout());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            return DatagramServerConnector.this.newConnection(endpoint);
        }

        @Override
        protected void endPointOpened(EndPoint endpoint)
        {
            super.endPointOpened(endpoint);
            onEndPointOpened(endpoint);
        }

        @Override
        protected void endPointClosed(EndPoint endpoint)
        {
            onEndPointClosed(endpoint);
            super.endPointClosed(endpoint);
        }

        private void setIdleTimeout(long idleTimeout)
        {
            getConnectedEndPoints().forEach(endPoint -> endPoint.setIdleTimeout(idleTimeout));
        }
    }
}
