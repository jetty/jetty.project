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

package org.eclipse.jetty.unixdomain.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A {@link Connector} implementation for Unix-Domain server socket channels.</p>
 * <p>UnixDomainServerConnector "listens" to a {@link #setUnixDomainPath(Path) Unix-Domain path}
 * and behaves {@link ServerConnector} with respect to acceptors, selectors and connection
 * factories.</p>
 * <p>Important: the unix-domain path must be less than 108 bytes.
 * This limit is set by the way Unix-Domain sockets work at the OS level.</p>
 */
@ManagedObject
public class UnixDomainServerConnector extends AbstractConnector
{
    public static final int MAX_UNIX_DOMAIN_PATH_LENGTH = 107;

    private final AtomicReference<Closeable> acceptor = new AtomicReference<>();
    private final SelectorManager selectorManager;
    private ServerSocketChannel serverChannel;
    private Path unixDomainPath;
    private boolean inheritChannel;
    private int acceptQueueSize;
    private int acceptedReceiveBufferSize;
    private int acceptedSendBufferSize;

    public UnixDomainServerConnector(Server server, ConnectionFactory... factories)
    {
        this(server, null, null, null, -1, -1, factories);
    }

    public UnixDomainServerConnector(Server server, int acceptors, int selectors, ConnectionFactory... factories)
    {
        this(server, null, null, null, acceptors, selectors, factories);
    }

    public UnixDomainServerConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool pool, int acceptors, int selectors, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, pool, acceptors, factories.length > 0 ? factories : new ConnectionFactory[]{new HttpConnectionFactory()});
        selectorManager = newSelectorManager(getExecutor(), getScheduler(), selectors);
        addBean(selectorManager, true);
    }

    protected SelectorManager newSelectorManager(Executor executor, Scheduler scheduler, int selectors)
    {
        return new UnixDomainSelectorManager(executor, scheduler, selectors);
    }

    @ManagedAttribute("The Unix-Domain path this connector listens to")
    public Path getUnixDomainPath()
    {
        return unixDomainPath;
    }

    public void setUnixDomainPath(Path unixDomainPath)
    {
        this.unixDomainPath = unixDomainPath;
    }

    @ManagedAttribute("Whether this connector uses a server channel inherited from the JVM")
    public boolean isInheritChannel()
    {
        return inheritChannel;
    }

    public void setInheritChannel(boolean inheritChannel)
    {
        this.inheritChannel = inheritChannel;
    }

    @ManagedAttribute("The accept queue size (backlog) for the server socket")
    public int getAcceptQueueSize()
    {
        return acceptQueueSize;
    }

    public void setAcceptQueueSize(int acceptQueueSize)
    {
        this.acceptQueueSize = acceptQueueSize;
    }

    @ManagedAttribute("The SO_RCVBUF option for accepted sockets")
    public int getAcceptedReceiveBufferSize()
    {
        return acceptedReceiveBufferSize;
    }

    public void setAcceptedReceiveBufferSize(int acceptedReceiveBufferSize)
    {
        this.acceptedReceiveBufferSize = acceptedReceiveBufferSize;
    }

    @ManagedAttribute("The SO_SNDBUF option for accepted sockets")
    public int getAcceptedSendBufferSize()
    {
        return acceptedSendBufferSize;
    }

    public void setAcceptedSendBufferSize(int acceptedSendBufferSize)
    {
        this.acceptedSendBufferSize = acceptedSendBufferSize;
    }

    @Override
    protected void doStart() throws Exception
    {
        getBeans(SelectorManager.SelectorManagerListener.class).forEach(selectorManager::addEventListener);
        serverChannel = open();
        addBean(serverChannel);
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        removeBean(serverChannel);
        close();
        getBeans(SelectorManager.SelectorManagerListener.class).forEach(selectorManager::removeEventListener);
    }

    @Override
    protected void accept(int acceptorID) throws IOException
    {
        ServerSocketChannel serverChannel = this.serverChannel;
        if (serverChannel != null)
        {
            SocketChannel channel = serverChannel.accept();
            accepted(channel);
        }
    }

    private void accepted(SocketChannel channel) throws IOException
    {
        channel.configureBlocking(false);
        configure(channel);
        selectorManager.accept(channel);
    }

    protected void configure(SocketChannel channel) throws IOException
    {
        // Unix-Domain does not support TCP_NODELAY.
        // Unix-Domain does not support SO_REUSEADDR.
        int rcvBufSize = getAcceptedReceiveBufferSize();
        if (rcvBufSize > 0)
            channel.setOption(StandardSocketOptions.SO_RCVBUF, rcvBufSize);
        int sndBufSize = getAcceptedSendBufferSize();
        if (sndBufSize > 0)
            channel.setOption(StandardSocketOptions.SO_SNDBUF, sndBufSize);
    }

    @Override
    public Object getTransport()
    {
        return serverChannel;
    }

    private ServerSocketChannel open() throws IOException
    {
        ServerSocketChannel serverChannel = openServerSocketChannel();
        if (getAcceptors() == 0)
        {
            serverChannel.configureBlocking(false);
            acceptor.set(selectorManager.acceptor(serverChannel));
        }
        return serverChannel;
    }

    private void close() throws IOException
    {
        ServerSocketChannel serverChannel = this.serverChannel;
        this.serverChannel = null;
        IO.close(serverChannel);
        Files.deleteIfExists(getUnixDomainPath());
    }

    private ServerSocketChannel openServerSocketChannel() throws IOException
    {
        ServerSocketChannel serverChannel = null;
        if (isInheritChannel())
        {
            Channel channel = System.inheritedChannel();
            if (channel instanceof ServerSocketChannel)
                serverChannel = (ServerSocketChannel)channel;
            else
                LOG.warn("Unable to use System.inheritedChannel() {}. Trying a new Unix-Domain ServerSocketChannel at {}", channel, getUnixDomainPath());
        }
        if (serverChannel == null)
            serverChannel = bindServerSocketChannel();
        return serverChannel;
    }

    private ServerSocketChannel bindServerSocketChannel() throws IOException
    {
        Path unixDomainPath = getUnixDomainPath();
        ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        SocketAddress socketAddress = UnixDomainSocketAddress.of(unixDomainPath);
        serverChannel.bind(socketAddress, getAcceptQueueSize());
        return serverChannel;
    }

    @Override
    public void setAccepting(boolean accepting)
    {
        super.setAccepting(accepting);
        if (getAcceptors() == 0)
            return;
        if (accepting)
        {
            if (acceptor.get() == null)
            {
                Closeable acceptor = selectorManager.acceptor(serverChannel);
                if (!this.acceptor.compareAndSet(null, acceptor))
                    IO.close(acceptor);
            }
        }
        else
        {
            Closeable acceptor = this.acceptor.get();
            if (acceptor != null && this.acceptor.compareAndSet(acceptor, null))
                IO.close(acceptor);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%h[%s]", getClass().getSimpleName(), hashCode(), getUnixDomainPath());
    }

    protected class UnixDomainSelectorManager extends SelectorManager
    {
        public UnixDomainSelectorManager(Executor executor, Scheduler scheduler, int selectors)
        {
            super(executor, scheduler, selectors);
        }

        @Override
        protected void accepted(SelectableChannel channel) throws IOException
        {
            UnixDomainServerConnector.this.accepted((SocketChannel)channel);
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey)
        {
            SocketChannelEndPoint endPoint = new SocketChannelEndPoint((SocketChannel)channel, selector, selectionKey, getScheduler());
            endPoint.setIdleTimeout(getIdleTimeout());
            return endPoint;
        }

        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            return getDefaultConnectionFactory().newConnection(UnixDomainServerConnector.this, endpoint);
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
    }
}
