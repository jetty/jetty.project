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

package org.eclipse.jetty.http3.client;

import java.io.Closeable;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.http3.common.QuicDatagramEndPoint;
import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientQuicConnector extends ClientConnector
{
    private static final Logger LOG = LoggerFactory.getLogger(ClientQuicConnector.class);

    private final QuicheConfig quicheConfig = new QuicheConfig();

    @Override
    public boolean isConnectBlocking()
    {
        return false;
    }

    @Override
    public void setConnectBlocking(boolean connectBlocking)
    {
    }

    @Override
    public boolean isIntrinsicallySecure()
    {
        return true;
    }

    @Override
    protected void doStart() throws Exception
    {
        // TODO: move the creation of quiche config to ClientQuicConnection.onOpen()

        // TODO make these QuicheConfig settings configurable
        quicheConfig.setDisableActiveMigration(true);
        quicheConfig.setVerifyPeer(false);
        quicheConfig.setMaxIdleTimeout(5000L);
        quicheConfig.setInitialMaxData(10_000_000L);
        quicheConfig.setInitialMaxStreamDataBidiLocal(10_000_000L);
        quicheConfig.setInitialMaxStreamDataUni(10_000_000L);
        quicheConfig.setInitialMaxStreamsBidi(100L);
        quicheConfig.setInitialMaxStreamsUni(100L);

        super.doStart();
    }

    @Override
    protected SelectorManager newSelectorManager()
    {
        return new QuicSelectorManager(getExecutor(), getScheduler(), 1);
    }

    @Override
    public void connect(SocketAddress address, Map<String, Object> context)
    {
        DatagramChannel channel = null;
        try
        {
            SelectorManager selectorManager = getBean(SelectorManager.class);
            if (context == null)
                context = new HashMap<>();
            context.put(ClientConnector.CLIENT_CONNECTOR_CONTEXT_KEY, this);
            context.putIfAbsent(REMOTE_SOCKET_ADDRESS_CONTEXT_KEY, address);

            channel = DatagramChannel.open();
            SocketAddress bindAddress = getBindAddress();
            if (bindAddress != null)
            {
                boolean reuseAddress = getReuseAddress();
                if (LOG.isDebugEnabled())
                    LOG.debug("Binding to {} to connect to {}{}", bindAddress, address, (reuseAddress ? " reusing address" : ""));
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, reuseAddress);
                channel.bind(bindAddress);
            }
            configure(channel);

            if (LOG.isDebugEnabled())
                LOG.debug("Connecting to {}", address);
            channel.configureBlocking(false);

            selectorManager.connect(channel, context);
        }
        // Must catch all exceptions, since some like
        // UnresolvedAddressException are not IOExceptions.
        catch (Throwable x)
        {
            // If IPv6 is not deployed, a generic SocketException "Network is unreachable"
            // exception is being thrown, so we attempt to provide a better error message.
            if (x.getClass() == SocketException.class)
                x = new SocketException("Could not connect to " + address).initCause(x);
            IO.close(channel);
            connectFailed(x, context);
        }
    }

    @Override
    public void accept(SelectableChannel selectableChannel, Map<String, Object> context)
    {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void configure(SelectableChannel selectableChannel)
    {
    }

    @Override
    protected EndPoint newEndPoint(SelectableChannel selectableChannel, ManagedSelector selector, SelectionKey selectionKey)
    {
        return new QuicDatagramEndPoint((DatagramChannel)selectableChannel, selector, selectionKey, getScheduler());
    }

    protected class QuicSelectorManager extends SelectorManager
    {
        public QuicSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        public void connect(SelectableChannel channel, Object attachment)
        {
            ManagedSelector managedSelector = chooseSelector();
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>)attachment;
            managedSelector.submit(new Connect(channel, context));
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
        {
            EndPoint endPoint = ClientQuicConnector.this.newEndPoint(channel, selector, selectionKey);
            endPoint.setIdleTimeout(getIdleTimeout().toMillis());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endPoint, Object attachment)
        {
            Connect connect = (Connect)attachment;
            Map<String, Object> contextMap = connect.getContext();
            return new ClientQuicConnection(getExecutor(), getScheduler(), getByteBufferPool(), endPoint, quicheConfig, contextMap);
        }

        @Override
        public void connectionOpened(Connection connection, Object attachment)
        {
            super.connectionOpened(connection, attachment);
            Connect connect = (Connect)attachment;
            Map<String, Object> contextMap = connect.getContext();
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)contextMap.get(CONNECTION_PROMISE_CONTEXT_KEY);
            if (promise != null)
                promise.succeeded(connection);
        }

        @Override
        protected void connectionFailed(SelectableChannel channel, Throwable failure, Object attachment)
        {
            Connect connect = (Connect)attachment;
            Map<String, Object> contextMap = connect.getContext();
            connectFailed(failure, contextMap);
        }

        class Connect implements ManagedSelector.SelectorUpdate, ManagedSelector.Selectable, Runnable, Closeable
        {
            private final AtomicBoolean failed = new AtomicBoolean();
            private final SelectableChannel channel;
            private final Map<String, Object> context;
            private volatile SelectionKey key;

            Connect(SelectableChannel channel, Map<String, Object> context)
            {
                this.channel = channel;
                this.context = context;
            }

            public Map<String, Object> getContext()
            {
                return context;
            }

            @Override
            public void update(Selector selector)
            {
                try
                {
                    key = channel.register(selector, SelectionKey.OP_WRITE, this);
                }
                catch (Throwable x)
                {
                    failed(x);
                }
            }

            @Override
            public Runnable onSelected()
            {
                key.interestOps(0);
                return this;
            }

            @Override
            public void run()
            {
                try
                {
                    chooseSelector().createEndPoint(channel, key);
                }
                catch (Throwable x)
                {
                    failed(x);
                }
            }

            @Override
            public void updateKey()
            {
            }

            @Override
            public void replaceKey(SelectionKey newKey)
            {
                key = newKey;
            }

            @Override
            public void close()
            {
                // May be called from any thread.
                // Implements AbstractConnector.setAccepting(boolean).
                chooseSelector().submit(selector -> key.cancel());
            }

            private void failed(Throwable failure)
            {
                if (failed.compareAndSet(false, true))
                {
                    IO.close(channel);
                    connectFailed(failure, context);
                }
            }
        }
    }
}
