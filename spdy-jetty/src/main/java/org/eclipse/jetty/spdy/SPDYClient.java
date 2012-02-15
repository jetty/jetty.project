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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
import org.eclipse.jetty.util.thread.ThreadPool;

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

        SessionFuture result = new SessionFuture(this, listener);

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

    protected AsyncConnectionFactory getAsyncConnectionFactory(String protocol)
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
        private final ThreadPool threadPool;
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

        public Factory(ThreadPool threadPool)
        {
            this(threadPool, null);
        }

        public Factory(ThreadPool threadPool, SslContextFactory sslContextFactory)
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

        public void join() throws InterruptedException
        {
            threadPool.join();
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

        private class ClientSelectorManager extends SelectorManager
        {
            @Override
            public boolean dispatch(Runnable task)
            {
                return threadPool.dispatch(task);
            }

            @Override
            protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
            {
                SessionFuture attachment = (SessionFuture)key.attachment();

                long maxIdleTime = attachment.getMaxIdleTime();
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
                SessionFuture sessionFuture = (SessionFuture)attachment;
                final SPDYClient client = sessionFuture.client;

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
                catch (Exception x)
                {
                    sessionFuture.failed(x);
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

    private static class SessionFuture implements Future<Session>
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final SPDYClient client;
        private final SessionFrameListener listener;
        private boolean cancelled;
        private Throwable failure;
        private Session session;

        private SessionFuture(SPDYClient client, SessionFrameListener listener)
        {
            this.client = client;
            this.listener = listener;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning)
        {
            cancelled = true;
            latch.countDown();
            return false;
        }

        @Override
        public boolean isCancelled()
        {
            return cancelled;
        }

        @Override
        public boolean isDone()
        {
            return cancelled || latch.getCount() == 0;
        }

        @Override
        public Session get() throws InterruptedException, ExecutionException
        {
            latch.await();
            return result();
        }

        @Override
        public Session get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
        {
            latch.await(timeout, unit);
            return result();
        }

        private Session result() throws ExecutionException
        {
            Throwable failure = this.failure;
            if (failure != null)
                throw new ExecutionException(failure);
            return session;
        }

        private long getMaxIdleTime()
        {
            return client.getMaxIdleTime();
        }

        private void failed(Throwable x)
        {
            this.failure = x;
            latch.countDown();
        }

        private void connected(Session session)
        {
            this.session = session;
            latch.countDown();
        }
    }

    private static class ClientSPDYAsyncConnectionFactory implements AsyncConnectionFactory
    {
        @Override
        public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
        {
            SessionFuture sessionFuture = (SessionFuture)attachment;

            CompressionFactory compressionFactory = new StandardCompressionFactory();
            Parser parser = new Parser(compressionFactory.newDecompressor());
            Generator generator = new Generator(compressionFactory.newCompressor());

            SPDYAsyncConnection connection = new SPDYAsyncConnection(endPoint, parser);
            endPoint.setConnection(connection);

            StandardSession session = new StandardSession(sessionFuture.client.version, connection, 1, sessionFuture.listener, generator);
            parser.addListener(session);
            sessionFuture.connected(session);
            connection.setSession(session);

            return connection;
        }
    }
}
