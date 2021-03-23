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
import java.io.IOException;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.http3.common.QuicDatagramEndPoint;
import org.eclipse.jetty.http3.quiche.QuicheConfig;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.IClientConnector;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientDatagramConnector extends ContainerLifeCycle implements IClientConnector
{
    public static final String CLIENT_CONNECTOR_CONTEXT_KEY = "org.eclipse.jetty.client.connector";
    public static final String REMOTE_SOCKET_ADDRESS_CONTEXT_KEY = CLIENT_CONNECTOR_CONTEXT_KEY + ".remoteSocketAddress";
    public static final String CLIENT_CONNECTION_FACTORY_CONTEXT_KEY = CLIENT_CONNECTOR_CONTEXT_KEY + ".clientConnectionFactory";
    public static final String CONNECTION_PROMISE_CONTEXT_KEY = CLIENT_CONNECTOR_CONTEXT_KEY + ".connectionPromise";
    private static final Logger LOG = LoggerFactory.getLogger(ClientConnector.class);

    private final QuicheConfig quicheConfig;
    private Executor executor;
    private Scheduler scheduler;
    private ByteBufferPool byteBufferPool;
    private SslContextFactory.Client sslContextFactory;
    private ClientDatagramSelectorManager selectorManager;
    private int selectors = 1;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration idleTimeout = Duration.ofSeconds(30);
    private SocketAddress bindAddress;
    private boolean reuseAddress = true;

    public ClientDatagramConnector(Origin.Protocol... protocols)
    {
        String[] applicationProtos = Arrays.stream(protocols)
            .flatMap(protocol -> protocol.getProtocols().stream())
            .toArray(String[]::new);

        // TODO make the QuicheConfig configurable
        quicheConfig = new QuicheConfig();
        quicheConfig.setApplicationProtos(applicationProtos);
        quicheConfig.setMaxIdleTimeout(5000L);
        quicheConfig.setInitialMaxData(10000000L);
        quicheConfig.setInitialMaxStreamDataBidiLocal(10000000L);
        quicheConfig.setInitialMaxStreamDataUni(10000000L);
        quicheConfig.setInitialMaxStreamsBidi(100L);
        quicheConfig.setInitialMaxStreamsUni(100L);
        quicheConfig.setDisableActiveMigration(true);
        quicheConfig.setVerifyPeer(false);
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public void setExecutor(Executor executor)
    {
        if (isStarted())
            throw new IllegalStateException();
        updateBean(this.executor, executor);
        this.executor = executor;
    }

    public Scheduler getScheduler()
    {
        return scheduler;
    }

    public void setScheduler(Scheduler scheduler)
    {
        if (isStarted())
            throw new IllegalStateException();
        updateBean(this.scheduler, scheduler);
        this.scheduler = scheduler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        if (isStarted())
            throw new IllegalStateException();
        updateBean(this.byteBufferPool, byteBufferPool);
        this.byteBufferPool = byteBufferPool;
    }

    public SslContextFactory.Client getSslContextFactory()
    {
        return sslContextFactory;
    }

    public void setSslContextFactory(SslContextFactory.Client sslContextFactory)
    {
        if (isStarted())
            throw new IllegalStateException();
        updateBean(this.sslContextFactory, sslContextFactory);
        this.sslContextFactory = sslContextFactory;
    }

    public int getSelectors()
    {
        return selectors;
    }

    public void setSelectors(int selectors)
    {
        if (isStarted())
            throw new IllegalStateException();
        this.selectors = selectors;
    }

    public boolean isConnectBlocking()
    {
        return false;
    }

    public void setConnectBlocking(boolean connectBlocking)
    {
    }

    public Duration getConnectTimeout()
    {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout)
    {
        this.connectTimeout = connectTimeout;
        if (selectorManager != null)
            selectorManager.setConnectTimeout(connectTimeout.toMillis());
    }

    public Duration getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(Duration idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public boolean getReuseAddress()
    {
        return reuseAddress;
    }

    public void setReuseAddress(boolean reuseAddress)
    {
        this.reuseAddress = reuseAddress;
    }

    @Override
    protected void doStart() throws Exception
    {
        if (executor == null)
        {
            QueuedThreadPool clientThreads = new QueuedThreadPool();
            clientThreads.setName(String.format("client-pool@%x", hashCode()));
            setExecutor(clientThreads);
        }
        if (scheduler == null)
            setScheduler(new ScheduledExecutorScheduler(String.format("client-scheduler@%x", hashCode()), false));
        if (byteBufferPool == null)
            setByteBufferPool(new MappedByteBufferPool());
        if (sslContextFactory == null)
            setSslContextFactory(newSslContextFactory());
        selectorManager = newSelectorManager();
        selectorManager.setConnectTimeout(getConnectTimeout().toMillis());
        addBean(selectorManager);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(selectorManager);
    }

    protected SslContextFactory.Client newSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(false);
        sslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");
        return sslContextFactory;
    }

    protected ClientDatagramSelectorManager newSelectorManager()
    {
        return new ClientDatagramSelectorManager(getExecutor(), getScheduler(), getSelectors());
    }

    public void connect(SocketAddress address, Map<String, Object> context)
    {
        DatagramChannel channel = null;
        try
        {
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

    public void accept(DatagramChannel channel, Map<String, Object> context)
    {
        try
        {
            context.put(ClientConnector.CLIENT_CONNECTOR_CONTEXT_KEY, this);
            if (!channel.isConnected())
                throw new IllegalStateException("DatagramChannel must be connected");
            configure(channel);
            channel.configureBlocking(false);
            selectorManager.accept(channel, context);
        }
        catch (Throwable failure)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not accept {}", channel);
            IO.close(channel);
            Promise<?> promise = (Promise<?>)context.get(CONNECTION_PROMISE_CONTEXT_KEY);
            if (promise != null)
                promise.failed(failure);
        }
    }

    protected void configure(DatagramChannel channel) throws IOException
    {
    }

    protected EndPoint newEndPoint(DatagramChannel channel, ManagedSelector selector, SelectionKey selectionKey)
    {
        return new QuicDatagramEndPoint(channel, selector, selectionKey, getScheduler());
    }

    protected void connectFailed(Throwable failure, Map<String, Object> context)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Could not connect to {}", context.get(REMOTE_SOCKET_ADDRESS_CONTEXT_KEY));
        Promise<?> promise = (Promise<?>)context.get(CONNECTION_PROMISE_CONTEXT_KEY);
        if (promise != null)
            promise.failed(failure);
    }

    protected class ClientDatagramSelectorManager extends SelectorManager
    {
        public ClientDatagramSelectorManager(Executor executor, Scheduler scheduler, int selectors)
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
            EndPoint endPoint = ClientDatagramConnector.this.newEndPoint((DatagramChannel)channel, selector, selectionKey);
            endPoint.setIdleTimeout(getIdleTimeout().toMillis());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endPoint, Object attachment)
        {
            Connect connect = (Connect)attachment;
            Map<String, Object> contextMap = connect.getContext();
            return new QuicConnection(executor, scheduler, byteBufferPool, endPoint, contextMap, quicheConfig);
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
            public void close() throws IOException
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
                    selectorManager.connectionFailed(channel, failure, context);
                }
            }
        }
    }
}
