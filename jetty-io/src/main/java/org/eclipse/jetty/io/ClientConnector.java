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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.JavaVersion;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClientConnector extends ContainerLifeCycle
{
    public static final String CLIENT_CONNECTOR_CONTEXT_KEY = "org.eclipse.jetty.client.connector";
    public static final String REMOTE_SOCKET_ADDRESS_CONTEXT_KEY = CLIENT_CONNECTOR_CONTEXT_KEY + ".remoteSocketAddress";
    public static final String CLIENT_CONNECTION_FACTORY_CONTEXT_KEY = CLIENT_CONNECTOR_CONTEXT_KEY + ".clientConnectionFactory";
    public static final String CONNECTION_PROMISE_CONTEXT_KEY = CLIENT_CONNECTOR_CONTEXT_KEY + ".connectionPromise";
    private static final Logger LOG = LoggerFactory.getLogger(ClientConnector.class);

    private final SocketChannelWithAddress.Factory factory;
    private Executor executor;
    private Scheduler scheduler;
    private ByteBufferPool byteBufferPool;
    private SslContextFactory.Client sslContextFactory;
    private SelectorManager selectorManager;
    private int selectors = 1;
    private boolean connectBlocking;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration idleTimeout = Duration.ofSeconds(30);
    private SocketAddress bindAddress;
    private boolean reuseAddress = true;

    public ClientConnector()
    {
        this((address, context) -> new SocketChannelWithAddress(SocketChannel.open(), address));
    }

    public ClientConnector(SocketChannelWithAddress.Factory factory)
    {
        this.factory = Objects.requireNonNull(factory);
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
        return connectBlocking;
    }

    public void setConnectBlocking(boolean connectBlocking)
    {
        this.connectBlocking = connectBlocking;
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

    protected SelectorManager newSelectorManager()
    {
        return new ClientSelectorManager(getExecutor(), getScheduler(), getSelectors());
    }

    public void connect(SocketAddress address, Map<String, Object> context)
    {
        SocketChannel channel = null;
        try
        {
            if (context == null)
                context = new HashMap<>();
            context.put(ClientConnector.CLIENT_CONNECTOR_CONTEXT_KEY, this);
            context.putIfAbsent(REMOTE_SOCKET_ADDRESS_CONTEXT_KEY, address);

            SocketChannelWithAddress channelWithAddress = factory.newSocketChannelWithAddress(address, context);
            channel = channelWithAddress.getSocketChannel();
            address = channelWithAddress.getSocketAddress();
            SocketAddress bindAddress = getBindAddress();
            if (bindAddress != null)
                bind(channel, bindAddress);
            configure(channel);

            boolean connected = true;
            boolean blocking = isConnectBlocking() && address instanceof InetSocketAddress;
            if (LOG.isDebugEnabled())
                LOG.debug("Connecting {} to {}", blocking ? "blocking" : "non-blocking", address);
            if (blocking)
            {
                channel.socket().connect(address, (int)getConnectTimeout().toMillis());
                channel.configureBlocking(false);
            }
            else
            {
                channel.configureBlocking(false);
                connected = channel.connect(address);
            }

            if (connected)
                selectorManager.accept(channel, context);
            else
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

    public void accept(SocketChannel channel, Map<String, Object> context)
    {
        try
        {
            context.put(ClientConnector.CLIENT_CONNECTOR_CONTEXT_KEY, this);
            if (!channel.isConnected())
                throw new IllegalStateException("SocketChannel must be connected");
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

    private void bind(SocketChannel channel, SocketAddress bindAddress)
    {
        try
        {
            boolean reuseAddress = getReuseAddress();
            if (LOG.isDebugEnabled())
                LOG.debug("Binding to {} reusing address {}", bindAddress, reuseAddress);
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, reuseAddress);
            channel.bind(bindAddress);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not bind {}", channel);
        }
    }

    protected void configure(SocketChannel channel) throws IOException
    {
        try
        {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not configure {}", channel);
        }
    }

    protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey selectionKey)
    {
        return new SocketChannelEndPoint(channel, selector, selectionKey, getScheduler());
    }

    protected void connectFailed(Throwable failure, Map<String, Object> context)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Could not connect to {}", context.get(REMOTE_SOCKET_ADDRESS_CONTEXT_KEY));
        Promise<?> promise = (Promise<?>)context.get(CONNECTION_PROMISE_CONTEXT_KEY);
        if (promise != null)
            promise.failed(failure);
    }

    protected class ClientSelectorManager extends SelectorManager
    {
        public ClientSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
        {
            EndPoint endPoint = ClientConnector.this.newEndPoint((SocketChannel)channel, selector, selectionKey);
            endPoint.setIdleTimeout(getIdleTimeout().toMillis());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endPoint, Object attachment) throws IOException
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>)attachment;
            ClientConnectionFactory factory = (ClientConnectionFactory)context.get(CLIENT_CONNECTION_FACTORY_CONTEXT_KEY);
            return factory.newConnection(endPoint, context);
        }

        @Override
        public void connectionOpened(Connection connection, Object context)
        {
            super.connectionOpened(connection, context);
            @SuppressWarnings("unchecked")
            Map<String, Object> contextMap = (Map<String, Object>)context;
            @SuppressWarnings("unchecked")
            Promise<Connection> promise = (Promise<Connection>)contextMap.get(CONNECTION_PROMISE_CONTEXT_KEY);
            if (promise != null)
                promise.succeeded(connection);
        }

        @Override
        protected void connectionFailed(SelectableChannel channel, Throwable failure, Object attachment)
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>)attachment;
            connectFailed(failure, context);
        }
    }

    /**
     * <p>A pair/record holding a {@link SocketChannel} and a {@link SocketAddress} to connect to.</p>
     */
    public static class SocketChannelWithAddress
    {
        private final SocketChannel channel;
        private final SocketAddress address;

        public SocketChannelWithAddress(SocketChannel channel, SocketAddress address)
        {
            this.channel = channel;
            this.address = address;
        }

        public SocketChannel getSocketChannel()
        {
            return channel;
        }

        public SocketAddress getSocketAddress()
        {
            return address;
        }

        /**
         * <p>A factory for {@link SocketChannelWithAddress} instances.</p>
         */
        public interface Factory
        {
            public static Factory forUnixDomain(Path path)
            {
                return (address, context) ->
                {
                    try
                    {
                        ProtocolFamily family = Enum.valueOf(StandardProtocolFamily.class, "UNIX");
                        SocketChannel socketChannel = (SocketChannel)SocketChannel.class.getMethod("open", ProtocolFamily.class).invoke(null, family);
                        Class<?> addressClass = Class.forName("java.net.UnixDomainSocketAddress");
                        SocketAddress socketAddress = (SocketAddress)addressClass.getMethod("of", Path.class).invoke(null, path);
                        return new SocketChannelWithAddress(socketChannel, socketAddress);
                    }
                    catch (Throwable x)
                    {
                        String message = "Unix-Domain SocketChannels are available starting from Java 16, your Java version is: " + JavaVersion.VERSION;
                        throw new UnsupportedOperationException(message, x);
                    }
                };
            }

            /**
             * <p>Creates a new {@link SocketChannel} to connect to a {@link SocketAddress}
             * derived from the input socket address.</p>
             * <p>The input socket address represents the destination socket address to
             * connect to, as it is typically specified by a URI authority, for example
             * {@code localhost:8080} if the URI is {@code http://localhost:8080/path}.</p>
             * <p>However, the returned socket address may be different as the implementation
             * may use a Unix-Domain socket address to physically connect to the virtual
             * destination socket address given as input.</p>
             * <p>The return type is a pair/record holding the socket channel and the
             * socket address, with the socket channel not yet connected.
             * The implementation of this methods must not call
             * {@link SocketChannel#connect(SocketAddress)}, as this is done later,
             * after configuring the socket, by the {@link ClientConnector} implementation.</p>
             *
             * @param address the destination socket address, typically specified in a URI
             * @param context the context to create the new socket channel
             * @return a new {@link SocketChannel} with an associated {@link SocketAddress} to connect to
             * @throws IOException if the socket channel or the socket address cannot be created
             */
            public SocketChannelWithAddress newSocketChannelWithAddress(SocketAddress address, Map<String, Object> context) throws IOException;
        }
    }
}
