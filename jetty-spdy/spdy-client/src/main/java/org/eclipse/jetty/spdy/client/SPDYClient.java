//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy.client;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.io.ssl.SslConnection.DecryptedEndPoint;
import org.eclipse.jetty.spdy.FlowControlStrategy;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * A {@link SPDYClient} allows applications to connect to one or more SPDY servers,
 * obtaining {@link Session} objects that can be used to send/receive SPDY frames.
 * <p />
 * {@link SPDYClient} instances are created through a {@link Factory}:
 * <pre>
 * SPDYClient.Factory factory = new SPDYClient.Factory();
 * SPDYClient client = factory.newSPDYClient(SPDY.V3);
 * </pre>
 * and then used to connect to the server:
 * <pre>
 * FuturePromise&lt;Session&gt; promise = new FuturePromise&lt;&gt;();
 * client.connect("server.com", null, promise);
 * Session session = promise.get();
 * </pre>
 */
public class SPDYClient
{
    private final SPDYClientConnectionFactory connectionFactory = new SPDYClientConnectionFactory();
    final short version;
    final Factory factory;
    private volatile SocketAddress bindAddress;
    private volatile long idleTimeout = -1;
    private volatile int initialWindowSize;
    private volatile boolean executeOnFillable;

    protected SPDYClient(short version, Factory factory)
    {
        this.version = version;
        this.factory = factory;
        setInitialWindowSize(65536);
    }

    /**
     * @return the address to bind the socket channel to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    /**
     * @param bindAddress the address to bind the socket channel to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    /**
     * Equivalent to:
     * <pre>
     * Future&lt;Session&gt; promise = new FuturePromise&lt;&gt;();
     * connect(address, listener, promise);
     * </pre>
     *
     * @param address the address to connect to
     * @param listener the session listener that will be notified of session events
     * @return a {@link Future} that provides a {@link Session} when connected
     */
    public Future<Session> connect(SocketAddress address, SessionFrameListener listener)
    {
        FuturePromise<Session> promise = new FuturePromise<>();
        connect(address, listener, promise);
        return promise;
    }

    /**
     * Connects to the given {@code address}, binding the given {@code listener} to session events,
     * and notified the given {@code promise} of the connect result.
     * <p />
     * If the connect operation is successful, the {@code promise} will be invoked with the {@link Session}
     * object that applications can use to perform SPDY requests.
     *
     * @param address the address to connect to
     * @param listener the session listener that will be notified of session events
     * @param promise the promise notified of connection success/failure
     */
    public void connect(SocketAddress address, SessionFrameListener listener, Promise<Session> promise)
    {
        if (!factory.isStarted())
            throw new IllegalStateException(Factory.class.getSimpleName() + " is not started");

        try
        {
            SocketChannel channel = SocketChannel.open();
            if (bindAddress != null)
                channel.bind(bindAddress);
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);

            SessionPromise result = new SessionPromise(promise, channel, this, listener);

            channel.connect(address);
            factory.selector.connect(channel, result);
        }
        catch (IOException x)
        {
            promise.failed(x);
        }
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public int getInitialWindowSize()
    {
        return initialWindowSize;
    }

    public void setInitialWindowSize(int initialWindowSize)
    {
        this.initialWindowSize = initialWindowSize;
    }

    public boolean isExecuteOnFillable()
    {
        return executeOnFillable;
    }

    public void setExecuteOnFillable(boolean executeOnFillable)
    {
        this.executeOnFillable = executeOnFillable;
    }

    protected String selectProtocol(List<String> serverProtocols)
    {
        String protocol = "spdy/" + version;
        for (String serverProtocol : serverProtocols)
        {
            if (serverProtocol.equals(protocol))
                return protocol;
        }
        return null;
    }

    public SPDYClientConnectionFactory getConnectionFactory()
    {
        return connectionFactory;
    }

    protected SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostName();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSSLEngine(peerHost, peerPort);
        engine.setUseClientMode(true);
        return engine;
    }

    protected FlowControlStrategy newFlowControlStrategy()
    {
        return FlowControlStrategyFactory.newFlowControlStrategy(version);
    }

    public static class Factory extends ContainerLifeCycle
    {
        private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();
        private final ByteBufferPool bufferPool = new MappedByteBufferPool();
        private final Scheduler scheduler;
        private final Executor executor;
        private final SslContextFactory sslContextFactory;
        private final SelectorManager selector;
        private final long idleTimeout;
        private long connectTimeout = 15000;

        public Factory()
        {
            this(null, null);
        }

        public Factory(SslContextFactory sslContextFactory)
        {
            this(null, null, sslContextFactory);
        }

        public Factory(Executor executor)
        {
            this(executor, null);
        }

        public Factory(Executor executor, Scheduler scheduler)
        {
            this(executor, scheduler, null);
        }

        public Factory(Executor executor, Scheduler scheduler, SslContextFactory sslContextFactory)
        {
            this(executor, scheduler, sslContextFactory, 30000);
        }

        public Factory(Executor executor, Scheduler scheduler, SslContextFactory sslContextFactory, long idleTimeout)
        {
            this.idleTimeout = idleTimeout;

            if (executor == null)
                executor = new QueuedThreadPool();
            this.executor = executor;
            addBean(executor);

            if (scheduler == null)
                scheduler = new ScheduledExecutorScheduler();
            this.scheduler = scheduler;
            addBean(scheduler);

            this.sslContextFactory = sslContextFactory;
            if (sslContextFactory != null)
                addBean(sslContextFactory);

            selector = new ClientSelectorManager(executor, scheduler);
            selector.setConnectTimeout(getConnectTimeout());
            addBean(selector);
        }

        public ByteBufferPool getByteBufferPool()
        {
            return bufferPool;
        }

        public Scheduler getScheduler()
        {
            return scheduler;
        }

        public Executor getExecutor()
        {
            return executor;
        }

        public long getConnectTimeout()
        {
            return connectTimeout;
        }

        public void setConnectTimeout(long connectTimeout)
        {
            this.connectTimeout = connectTimeout;
        }

        public SPDYClient newSPDYClient(short version)
        {
            return new SPDYClient(version, this);
        }

        @Override
        protected void doStop() throws Exception
        {
            closeConnections();
            super.doStop();
        }

        boolean sessionOpened(Session session)
        {
            // Add sessions only if the factory is not stopping
            return isRunning() && sessions.offer(session);
        }

        boolean sessionClosed(Session session)
        {
            // Remove sessions only if the factory is not stopping
            // to avoid concurrent removes during iterations
            return isRunning() && sessions.remove(session);
        }

        private void closeConnections()
        {
            for (Session session : sessions)
                session.goAway(new GoAwayInfo(), new Callback.Adapter());
            sessions.clear();
        }

        public Collection<Session> getSessions()
        {
            return Collections.unmodifiableCollection(sessions);
        }

        private class ClientSelectorManager extends SelectorManager
        {
            private ClientSelectorManager(Executor executor, Scheduler scheduler)
            {
                super(executor, scheduler);
            }

            @Override
            protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
            {
                SessionPromise attachment = (SessionPromise)key.attachment();

                long clientIdleTimeout = attachment.client.getIdleTimeout();
                if (clientIdleTimeout < 0)
                    clientIdleTimeout = idleTimeout;

                return new SelectChannelEndPoint(channel, selectSet, key, getScheduler(), clientIdleTimeout);
            }

            @Override
            public Connection newConnection(final SocketChannel channel, EndPoint endPoint, final Object attachment)
            {
                SessionPromise sessionPromise = (SessionPromise)attachment;
                final SPDYClient client = sessionPromise.client;

                try
                {
                    if (sslContextFactory != null)
                    {
                        final SSLEngine engine = client.newSSLEngine(sslContextFactory, channel);
                        SslConnection sslConnection = new SslConnection(bufferPool, getExecutor(), endPoint, engine);
                        sslConnection.setRenegotiationAllowed(sslContextFactory.isRenegotiationAllowed());
                        DecryptedEndPoint sslEndPoint = sslConnection.getDecryptedEndPoint();
                        NextProtoNegoClientConnection connection = new NextProtoNegoClientConnection(channel, sslEndPoint, attachment, getExecutor(), client);
                        sslEndPoint.setConnection(connection);
                        return sslConnection;
                    }

                    SPDYClientConnectionFactory connectionFactory = new SPDYClientConnectionFactory();
                    return connectionFactory.newConnection(channel, endPoint, attachment);
                }
                catch (RuntimeException x)
                {
                    sessionPromise.failed(x);
                    throw x;
                }
            }
        }
    }

    static class SessionPromise extends FuturePromise<Session>
    {
        private final SocketChannel channel;
        private final Promise wrappedPromise;
        final SPDYClient client;
        final SessionFrameListener listener;

        private SessionPromise(Promise<Session> promise, SocketChannel channel, SPDYClient client,
                               SessionFrameListener listener)
        {
            this.channel = channel;
            this.client = client;
            this.listener = listener;
            this.wrappedPromise = promise;
        }

        @Override
        public void succeeded(Session result)
        {
            wrappedPromise.succeeded(result);
            super.succeeded(result);
        }

        @Override
        public void failed(Throwable cause)
        {
            wrappedPromise.failed(cause);
            super.failed(cause);
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {
            try
            {
                super.cancel(mayInterruptIfRunning);
                channel.close();
                return true;
            }
            catch (IOException x)
            {
                return true;
            }
        }
    }
}
