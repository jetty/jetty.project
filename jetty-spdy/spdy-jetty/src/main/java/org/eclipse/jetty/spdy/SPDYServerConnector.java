// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SPDYServerConnector extends SelectChannelConnector
{
    private static final Logger logger = Log.getLogger(SPDYServerConnector.class);

    // Order is important on server side, so we use a LinkedHashMap
    private final Map<String, AsyncConnectionFactory> factories = new LinkedHashMap<>();
    private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();
    private final ByteBufferPool bufferPool = new StandardByteBufferPool();
    private final Executor executor = new LazyExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ServerSessionFrameListener listener;
    private final SslContextFactory sslContextFactory;
    private volatile AsyncConnectionFactory defaultConnectionFactory;
    private volatile int initialWindowSize = 65536;

    public SPDYServerConnector(ServerSessionFrameListener listener)
    {
        this(listener, null);
    }

    public SPDYServerConnector(ServerSessionFrameListener listener, SslContextFactory sslContextFactory)
    {
        this.listener = listener;
        this.sslContextFactory = sslContextFactory;
        if (sslContextFactory != null)
            addBean(sslContextFactory);
        putAsyncConnectionFactory("spdy/3", new ServerSPDYAsyncConnectionFactory(SPDY.V3, bufferPool, executor, scheduler, listener));
        putAsyncConnectionFactory("spdy/2", new ServerSPDYAsyncConnectionFactory(SPDY.V2, bufferPool, executor, scheduler, listener));
        setDefaultAsyncConnectionFactory(getAsyncConnectionFactory("spdy/2"));
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public ScheduledExecutorService getScheduler()
    {
        return scheduler;
    }

    public ServerSessionFrameListener getServerSessionFrameListener()
    {
        return listener;
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        logger.info("SPDY support is experimental. Please report feedback at jetty-dev@eclipse.org");
    }

    @Override
    protected void doStop() throws Exception
    {
        closeSessions();
        scheduler.shutdown();
        super.doStop();
    }

    @Override
    public void join() throws InterruptedException
    {
        scheduler.awaitTermination(0, TimeUnit.MILLISECONDS);
        super.join();
    }

    public AsyncConnectionFactory getAsyncConnectionFactory(String protocol)
    {
        synchronized (factories)
        {
            return factories.get(protocol);
        }
    }

    public AsyncConnectionFactory putAsyncConnectionFactory(String protocol, AsyncConnectionFactory factory)
    {
        synchronized (factories)
        {
            return factories.put(protocol, factory);
        }
    }

    public AsyncConnectionFactory removeAsyncConnectionFactory(String protocol)
    {
        synchronized (factories)
        {
            return factories.remove(protocol);
        }
    }

    public Map<String, AsyncConnectionFactory> getAsyncConnectionFactories()
    {
        synchronized (factories)
        {
            return new LinkedHashMap<>(factories);
        }
    }

    public void clearAsyncConnectionFactories()
    {
        synchronized (factories)
        {
            factories.clear();
        }
    }

    protected List<String> provideProtocols()
    {
        synchronized (factories)
        {
            return new ArrayList<>(factories.keySet());
        }
    }

    public AsyncConnectionFactory getDefaultAsyncConnectionFactory()
    {
        return defaultConnectionFactory;
    }

    public void setDefaultAsyncConnectionFactory(AsyncConnectionFactory defaultConnectionFactory)
    {
        this.defaultConnectionFactory = defaultConnectionFactory;
    }

    @Override
    protected Connection newConnection(final SocketChannel channel, EndPoint endPoint)
    {
        if (sslContextFactory != null)
        {
            final SSLEngine engine = newSSLEngine(sslContextFactory, channel);
            Executor executor = getExecutor();
            SslConnection sslConnection = new SslConnection(bufferPool, executor, endPoint, engine)
            {
                @Override
                public void onClose()
                {
                    NextProtoNego.remove(engine);
                    super.onClose();
                }
            };

            final EndPoint sslEndPoint = sslConnection.getDecryptedEndPoint();
            NextProtoNegoServerAsyncConnection connection = new NextProtoNegoServerAsyncConnection(channel, sslEndPoint, this);
            sslEndPoint.setConnection(connection);
            getSelectorManager().connectionOpened(connection);

            NextProtoNego.put(engine, connection);

            return sslConnection;
        }
        else
        {
            AsyncConnectionFactory connectionFactory = getDefaultAsyncConnectionFactory();
            Connection connection = connectionFactory.newAsyncConnection(channel, endPoint, this);
            endPoint.setConnection(connection);
            return connection;
        }
    }

    protected FlowControlStrategy newFlowControlStrategy(short version)
    {
        return FlowControlStrategyFactory.newFlowControlStrategy(version);
    }

    protected SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSslEngine(peerHost, peerPort);
        engine.setUseClientMode(false);
        return engine;
    }

    protected boolean sessionOpened(Session session)
    {
        // Add sessions only if the connector is not stopping
        return isRunning() && sessions.offer(session);
    }

    protected boolean sessionClosed(Session session)
    {
        // Remove sessions only if the connector is not stopping
        // to avoid concurrent removes during iterations
        return isRunning() && sessions.remove(session);
    }

    private void closeSessions()
    {
        for (Session session : sessions)
            session.goAway();
        sessions.clear();
    }

    protected Collection<Session> getSessions()
    {
        return Collections.unmodifiableCollection(sessions);
    }

    public int getInitialWindowSize()
    {
        return initialWindowSize;
    }

    public void setInitialWindowSize(int initialWindowSize)
    {
        this.initialWindowSize = initialWindowSize;
    }

    public void replaceAsyncConnection(EndPoint endPoint, Connection connection)
    {
        Connection oldConnection = endPoint.getConnection();
        endPoint.setConnection(connection);
        getSelectorManager().connectionUpgraded(endPoint, oldConnection);
    }

    private class LazyExecutor implements Executor
    {
        @Override
        public void execute(Runnable command)
        {
            Executor threadPool = getExecutor();
            if (threadPool == null)
                throw new RejectedExecutionException();
            threadPool.execute(command);
        }
    }


    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        super.dump(out,indent);
        AggregateLifeCycle.dump(out, indent, new ArrayList<Session>(sessions));
    }


}
