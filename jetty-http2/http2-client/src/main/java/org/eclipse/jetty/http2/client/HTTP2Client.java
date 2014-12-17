//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

import org.eclipse.jetty.alpn.client.ALPNClientConnectionFactory;
import org.eclipse.jetty.http2.ErrorCodes;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;

public class HTTP2Client extends ContainerLifeCycle
{
    private final Queue<ISession> sessions = new ConcurrentLinkedQueue<>();
    private final SelectorManager selector;
    private final ByteBufferPool byteBufferPool;
    private long idleTimeout;

    public HTTP2Client()
    {
        this(new QueuedThreadPool());
    }

    public HTTP2Client(Executor executor)
    {
        addBean(executor);
        Scheduler scheduler = new ScheduledExecutorScheduler();
        addBean(scheduler, true);
        this.selector = new ClientSelectorManager(executor, scheduler);
        addBean(selector, true);
        this.byteBufferPool = new MappedByteBufferPool();
        addBean(byteBufferPool, true);
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
        super.doStop();
    }

    public void connect(InetSocketAddress address, Session.Listener listener, Promise<Session> promise)
    {
        connect(null, address, listener, promise);
    }

    public void connect(SslContextFactory sslContextFactory, InetSocketAddress address, Session.Listener listener, Promise<Session> promise)
    {
        try
        {
            SocketChannel channel = SocketChannel.open();
            channel.socket().setTcpNoDelay(true);
            channel.configureBlocking(false);

            Map<String, Object> context = new HashMap<>();
            context.put(HTTP2ClientConnectionFactory.CLIENT_CONTEXT_KEY, this);
            context.put(HTTP2ClientConnectionFactory.SESSION_LISTENER_CONTEXT_KEY, listener);
            context.put(HTTP2ClientConnectionFactory.SESSION_PROMISE_CONTEXT_KEY, promise);
            if (sslContextFactory != null)
                context.put(SslClientConnectionFactory.SSL_CONTEXT_FACTORY_CONTEXT_KEY, sslContextFactory);
            context.put(SslClientConnectionFactory.SSL_PEER_HOST_CONTEXT_KEY, address.getHostString());
            context.put(SslClientConnectionFactory.SSL_PEER_PORT_CONTEXT_KEY, address.getPort());

            if (channel.connect(address))
                selector.accept(channel, context);
            else
                selector.connect(channel, context);
        }
        catch (Throwable x)
        {
            promise.failed(x);
        }
    }

    private void closeConnections()
    {
        for (ISession session : sessions)
            session.close(ErrorCodes.NO_ERROR, null, Callback.Adapter.INSTANCE);
        sessions.clear();
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    public boolean addSession(ISession session)
    {
        return sessions.offer(session);
    }

    public boolean removeSession(ISession session)
    {
        return sessions.remove(session);
    }

    private class ClientSelectorManager extends SelectorManager
    {
        private ClientSelectorManager(Executor executor, Scheduler scheduler)
        {
            super(executor, scheduler);
        }

        @Override
        protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
        {
            return new SelectChannelEndPoint(channel, selector, selectionKey, getScheduler(), getIdleTimeout());
        }

        @Override
        public Connection newConnection(SocketChannel channel, EndPoint endpoint, Object attachment) throws IOException
        {
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>)attachment;
            context.put(HTTP2ClientConnectionFactory.BYTE_BUFFER_POOL_CONTEXT_KEY, byteBufferPool);
            context.put(HTTP2ClientConnectionFactory.EXECUTOR_CONTEXT_KEY, getExecutor());
            context.put(HTTP2ClientConnectionFactory.SCHEDULER_CONTEXT_KEY, getScheduler());

            ClientConnectionFactory factory = new HTTP2ClientConnectionFactory();

            SslContextFactory sslContextFactory = (SslContextFactory)context.get(SslClientConnectionFactory.SSL_CONTEXT_FACTORY_CONTEXT_KEY);
            if (sslContextFactory != null)
            {
                ALPNClientConnectionFactory alpn = new ALPNClientConnectionFactory(getExecutor(), factory, "h2-14");
                factory = new SslClientConnectionFactory(sslContextFactory, byteBufferPool, getExecutor(), alpn);
            }

            return factory.newConnection(endpoint, context);
        }
    }
}
