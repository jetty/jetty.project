//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.EventListener;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.server.AbstractNetworkConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.thread.Scheduler;

public class ServerDatagramConnector extends AbstractNetworkConnector
{
    private final ServerDatagramSelectorManager _manager;
    private volatile DatagramChannel _datagramChannel;
    private volatile int _localPort = -1;
    private Closeable _acceptor;

    public ServerDatagramConnector(
        @Name("server") Server server,
        @Name("executor") Executor executor,
        @Name("scheduler") Scheduler scheduler,
        @Name("bufferPool") ByteBufferPool bufferPool,
        @Name("selectors") int selectors,
        @Name("factories") ConnectionFactory... factories)
    {
        super(server, executor, scheduler, bufferPool, 0, factories);
        _manager = new ServerDatagramSelectorManager(getExecutor(), getScheduler(), selectors);
        addBean(_manager, true);
        setAcceptorPriorityDelta(-2);
    }

    public ServerDatagramConnector(
        @Name("server") Server server,
        @Name("factories") ConnectionFactory... factories)
    {
        this(server, null, null, null, 1, factories);
    }

    @Override
    protected void doStart() throws Exception
    {
        for (EventListener l : getBeans(SelectorManager.SelectorManagerListener.class))
            _manager.addEventListener(l);
        super.doStart();
        _acceptor = _manager.datagramReader(_datagramChannel);
    }

    @Override
    protected void doStop() throws Exception
    {
        IO.close(_acceptor);
        super.doStop();
        for (EventListener l : getBeans(EventListener.class))
            _manager.removeEventListener(l);
    }

    @Override
    public boolean isOpen()
    {
        DatagramChannel channel = _datagramChannel;
        return channel != null && channel.isOpen();
    }

    @Override
    public void open() throws IOException
    {
        if (_datagramChannel == null)
        {
            _datagramChannel = openDatagramChannel();
            _datagramChannel.configureBlocking(false);
            _localPort = _datagramChannel.socket().getLocalPort();
            if (_localPort <= 0)
                throw new IOException("Datagram channel not bound");
            addBean(_datagramChannel);
        }
    }

    @Override
    public void close()
    {
        super.close();

        DatagramChannel datagramChannel = _datagramChannel;
        _datagramChannel = null;
        if (datagramChannel != null)
        {
            removeBean(datagramChannel);

            if (datagramChannel.isOpen())
            {
                try
                {
                    datagramChannel.close();
                }
                catch (IOException e)
                {
                    LOG.warn("Unable to close {}", datagramChannel, e);
                }
            }
        }
        _localPort = -2;
    }

    protected DatagramChannel openDatagramChannel() throws IOException
    {
        InetSocketAddress bindAddress = getHost() == null ? new InetSocketAddress(getPort()) : new InetSocketAddress(getHost(), getPort());
        DatagramChannel datagramChannel = DatagramChannel.open();
        try
        {
            datagramChannel.socket().bind(bindAddress);
        }
        catch (Throwable e)
        {
            IO.close(datagramChannel);
            throw new IOException("Failed to bind to " + bindAddress, e);
        }
        return datagramChannel;
    }

    @Override
    public Object getTransport()
    {
        return _datagramChannel;
    }

    @Override
    protected void accept(int acceptorID)
    {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " has no accept mechanism");
    }

    private class ServerDatagramSelectorManager extends SelectorManager
    {
        protected ServerDatagramSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        public Closeable datagramReader(SelectableChannel server)
        {
            ManagedSelector selector = chooseSelector();
            DatagramReader reader = new DatagramReader(server);
            selector.submit(reader);
            return reader;
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
        {
            return new ServerDatagramEndPoint((DatagramChannel)channel, selector, selectionKey, getScheduler());
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException
        {
            return new QuicConnection(ServerDatagramConnector.this, (ServerDatagramEndPoint)endpoint);
        }

        @Override
        public String toString()
        {
            return String.format("DatagramSelectorManager@%s", ServerDatagramConnector.this);
        }

        class DatagramReader implements ManagedSelector.SelectorUpdate, ManagedSelector.Selectable, Closeable
        {
            private final SelectableChannel _channel;
            private volatile boolean endPointCreated;
            private volatile SelectionKey _key;

            DatagramReader(SelectableChannel channel)
            {
                _channel = channel;
            }

            @Override
            public void update(Selector selector)
            {
                try
                {
                    _key = _channel.register(selector, SelectionKey.OP_READ, this);
                    if (LOG.isDebugEnabled())
                        LOG.debug("{} reader={}", this, _channel);
                }
                catch (Throwable x)
                {
                    IO.close(_channel);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Unable to register OP_READ on selector for {}", _channel, x);
                }
            }

            @Override
            public Runnable onSelected()
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("DatagramReader onSelected");
                if (!endPointCreated)
                {
                    try
                    {
                        // TODO needs to be dispatched.
                        chooseSelector().createEndPoint(_channel, _key);
                        endPointCreated = true;
                    }
                    catch (Throwable x)
                    {
                        IO.close(_datagramChannel);
                        // TODO: is this enough of we need to notify someone?
                        if (LOG.isDebugEnabled())
                            LOG.debug("createEndPoint failed for channel {}", _channel, x);
                    }
                }
                return null;
            }

            @Override
            public void updateKey()
            {
            }

            @Override
            public void replaceKey(SelectionKey newKey)
            {
                _key = newKey;
            }

            @Override
            public void close() throws IOException
            {
                // May be called from any thread.
                // Implements AbstractConnector.setAccepting(boolean).
                chooseSelector().submit(selector -> _key.cancel());
            }
        }
    }
}
