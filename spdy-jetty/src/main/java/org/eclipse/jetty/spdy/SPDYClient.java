/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.io.nio.SslConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.SessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

public class SPDYClient
{
    private final Map<String, AsyncConnectionFactory> factories = new ConcurrentHashMap<>();
    private final short version;
    private final Factory factory;
    private SocketAddress bindAddress;
    private long maxIdleTime;

    protected SPDYClient(short version, Factory factory)
    {
        this.version = version;
        this.factory = factory;
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

    public Future<Session> connect(InetSocketAddress address, SessionFrameListener listener) throws IOException
    {
        if (!factory.isStarted())
            throw new IllegalStateException(Factory.class.getSimpleName() + " is not started");

        SocketChannel channel = SocketChannel.open();
        if (bindAddress != null)
            channel.bind(bindAddress);
        channel.socket().setTcpNoDelay(true);
        channel.configureBlocking(false);

        SessionPromise result = new SessionPromise(this, listener);

        channel.connect(address);
        factory.selector.register(channel, result);

        return result;
    }

    public long getMaxIdleTime()
    {
        return maxIdleTime;
    }

    public void setMaxIdleTime(long maxIdleTime)
    {
        this.maxIdleTime = maxIdleTime;
    }

    protected String selectProtocol(List<String> serverProtocols)
    {
        if (serverProtocols == null)
            return "spdy/2";

        for (String serverProtocol : serverProtocols)
        {
            for (String protocol : factories.keySet())
            {
                if (serverProtocol.equals(protocol))
                    return protocol;
            }
            String protocol = factory.selectProtocol(serverProtocols);
            if (protocol != null)
                return protocol;
        }

        return null;
    }

    public AsyncConnectionFactory getAsyncConnectionFactory(String protocol)
    {
        for (Map.Entry<String, AsyncConnectionFactory> entry : factories.entrySet())
        {
            if (protocol.equals(entry.getKey()))
                return entry.getValue();
        }
        for (Map.Entry<String, AsyncConnectionFactory> entry : factory.factories.entrySet())
        {
            if (protocol.equals(entry.getKey()))
                return entry.getValue();
        }
        return null;
    }

    public void putAsyncConnectionFactory(String protocol, AsyncConnectionFactory factory)
    {
        factories.put(protocol, factory);
    }

    public AsyncConnectionFactory removeAsyncConnectionFactory(String protocol)
    {
        return factories.remove(protocol);
    }

    protected SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSslEngine(peerHost, peerPort);
        engine.setUseClientMode(true);
        return engine;
    }

    public static class Factory extends AggregateLifeCycle
    {
        private final Map<String, AsyncConnectionFactory> factories = new ConcurrentHashMap<>();
        private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();
        private final ByteBufferPool bufferPool = new StandardByteBufferPool();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        private final Executor threadPool;
        private final SslContextFactory sslContextFactory;
        private final SelectorManager selector;

        public Factory()
        {
            this(null, null);
        }

        public Factory(SslContextFactory sslContextFactory)
        {
            this(null, sslContextFactory);
        }

        public Factory(Executor threadPool)
        {
            this(threadPool, null);
        }

        public Factory(Executor threadPool, SslContextFactory sslContextFactory)
        {
            if (threadPool == null)
                threadPool = new QueuedThreadPool();
            this.threadPool = threadPool;
            addBean(threadPool);

            this.sslContextFactory = sslContextFactory;
            if (sslContextFactory != null)
                addBean(sslContextFactory);

            selector = new ClientSelectorManager();
            addBean(selector);

            factories.put("spdy/2", new ClientSPDYAsyncConnectionFactory());
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

        protected String selectProtocol(List<String> serverProtocols)
        {
            for (String serverProtocol : serverProtocols)
            {
                for (String protocol : factories.keySet())
                {
                    if (serverProtocol.equals(protocol))
                        return protocol;
                }
            }
            return null;
        }

        private boolean sessionOpened(Session session)
        {
            // Add sessions only if the factory is not stopping
            return isRunning() && sessions.offer(session);
        }

        private boolean sessionClosed(Session session)
        {
            // Remove sessions only if the factory is not stopping
            // to avoid concurrent removes during iterations
            return isRunning() && sessions.remove(session);
        }

        private void closeConnections()
        {
            for (Session session : sessions)
                session.goAway();
            sessions.clear();
        }

        protected Collection<Session> getSessions()
        {
            return Collections.unmodifiableCollection(sessions);
        }

        private class ClientSelectorManager extends SelectorManager
        {
            @Override
            public boolean dispatch(Runnable task)
            {
                try
                {
                    threadPool.execute(task);
                    return true;
                }
                catch (RejectedExecutionException x)
                {
                    return false;
                }
            }

            @Override
            protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
            {
                SessionPromise attachment = (SessionPromise)key.attachment();

                long maxIdleTime = attachment.client.getMaxIdleTime();
                if (maxIdleTime < 0)
                    maxIdleTime = getMaxIdleTime();
                SelectChannelEndPoint result = new SelectChannelEndPoint(channel, selectSet, key, (int)maxIdleTime);

                AsyncConnection connection = newConnection(channel, result, attachment);
                result.setConnection(connection);

                return result;
            }

            @Override
            protected void endPointOpened(SelectChannelEndPoint endpoint)
            {
            }

            @Override
            protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
            {
            }

            @Override
            protected void endPointClosed(SelectChannelEndPoint endpoint)
            {
                endpoint.getConnection().onClose();
            }

            @Override
            public AsyncConnection newConnection(final SocketChannel channel, AsyncEndPoint endPoint, final Object attachment)
            {
                SessionPromise sessionPromise = (SessionPromise)attachment;
                final SPDYClient client = sessionPromise.client;

                try
                {
                    if (sslContextFactory != null)
                    {
                        SSLEngine engine = client.newSSLEngine(sslContextFactory, channel);
                        SslConnection sslConnection = new SslConnection(engine, endPoint);
                        endPoint.setConnection(sslConnection);
                        final AsyncEndPoint sslEndPoint = sslConnection.getSslEndPoint();

                        NextProtoNego.put(engine, new NextProtoNego.ClientProvider()
                        {
                            @Override
                            public boolean supports()
                            {
                                return true;
                            }

                            @Override
                            public void unsupported()
                            {
                                // Server does not support NPN, but this is a SPDY client, so hardcode SPDY
                                ClientSPDYAsyncConnectionFactory connectionFactory = new ClientSPDYAsyncConnectionFactory();
                                AsyncConnection connection = connectionFactory.newAsyncConnection(channel, sslEndPoint, attachment);
                                sslEndPoint.setConnection(connection);
                            }

                            @Override
                            public String selectProtocol(List<String> protocols)
                            {
                                String protocol = client.selectProtocol(protocols);
                                if (protocol == null)
                                    return null;

                                AsyncConnectionFactory connectionFactory = client.getAsyncConnectionFactory(protocol);
                                AsyncConnection connection = connectionFactory.newAsyncConnection(channel, sslEndPoint, attachment);
                                sslEndPoint.setConnection(connection);

                                return protocol;
                            }
                        });

                        AsyncConnection connection = new EmptyAsyncConnection(sslEndPoint);
                        sslEndPoint.setConnection(connection);

                        startHandshake(engine);

                        return sslConnection;
                    }
                    else
                    {
                        AsyncConnectionFactory connectionFactory = new ClientSPDYAsyncConnectionFactory();
                        AsyncConnection connection = connectionFactory.newAsyncConnection(channel, endPoint, attachment);
                        endPoint.setConnection(connection);
                        return connection;
                    }
                }
                catch (RuntimeException x)
                {
                    sessionPromise.failed(x);
                    throw x;
                }
            }

            private void startHandshake(SSLEngine engine)
            {
                try
                {
                    engine.beginHandshake();
                }
                catch (SSLException x)
                {
                    throw new RuntimeException(x);
                }
            }
        }
    }

    private static class SessionPromise extends Promise<Session>
    {
        private final SPDYClient client;
        private final SessionFrameListener listener;

        private SessionPromise(SPDYClient client, SessionFrameListener listener)
        {
            this.client = client;
            this.listener = listener;
        }
    }

    private static class ClientSPDYAsyncConnectionFactory implements AsyncConnectionFactory
    {
        @Override
        public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
        {
            SessionPromise sessionPromise = (SessionPromise)attachment;
            Factory factory = sessionPromise.client.factory;

            CompressionFactory compressionFactory = new StandardCompressionFactory();
            Parser parser = new Parser(compressionFactory.newDecompressor());
            Generator generator = new Generator(factory.bufferPool, compressionFactory.newCompressor());

            SPDYAsyncConnection connection = new ClientSPDYAsyncConnection(endPoint, factory.bufferPool, parser, factory);
            endPoint.setConnection(connection);

            StandardSession session = new StandardSession(sessionPromise.client.version, factory.bufferPool, factory.threadPool, factory.scheduler, connection, connection, 1, sessionPromise.listener, generator);
            parser.addListener(session);
            sessionPromise.completed(session);
            connection.setSession(session);

            factory.sessionOpened(session);

            return connection;
        }

        private class ClientSPDYAsyncConnection extends SPDYAsyncConnection
        {
            private final Factory factory;

            public ClientSPDYAsyncConnection(AsyncEndPoint endPoint, ByteBufferPool bufferPool, Parser parser, Factory factory)
            {
                super(endPoint, bufferPool, parser);
                this.factory = factory;
            }

            @Override
            public void onClose()
            {
                super.onClose();
                factory.sessionClosed(getSession());
            }
        }
    }
}
