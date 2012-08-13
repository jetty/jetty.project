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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.SelectChannelConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public class SPDYServerConnector extends SelectChannelConnector
{
    private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();
    private final ServerSessionFrameListener listener;
    private volatile int initialWindowSize;

    public SPDYServerConnector(Server server, ServerSessionFrameListener listener)
    {
        this(server, null, listener);
    }

    public SPDYServerConnector(Server server, SslContextFactory sslContextFactory, ServerSessionFrameListener listener)
    {
        super(server, sslContextFactory);
        this.listener = listener;
        setInitialWindowSize(65536);
        putConnectionFactory("spdy/3", new ServerSPDYConnectionFactory(SPDY.V3, getByteBufferPool(), getExecutor(), getScheduler(), listener));
        putConnectionFactory("spdy/2", new ServerSPDYConnectionFactory(SPDY.V2, getByteBufferPool(), getExecutor(), getScheduler(), listener));
        setDefaultConnectionFactory(getConnectionFactory("spdy/2"));
    }

    public ServerSessionFrameListener getServerSessionFrameListener()
    {
        return listener;
    }

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        LOG.info("SPDY support is experimental. Please report feedback at jetty-dev@eclipse.org");
    }

    @Override
    protected void doStop() throws Exception
    {
        closeSessions();
        super.doStop();
    }

    protected List<String> provideProtocols()
    {
        return new ArrayList<>(getConnectionFactories().keySet());
    }

    @Override
    protected Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment)
    {
        SslContextFactory sslContextFactory = getSslContextFactory();
        if (sslContextFactory != null)
        {
            final SSLEngine engine = newSSLEngine(sslContextFactory, channel);
            SslConnection sslConnection = new SslConnection(getByteBufferPool(), getExecutor(), endPoint, engine)
            {
                @Override
                public void onClose()
                {
                    NextProtoNego.remove(engine);
                    super.onClose();
                }
            };

            final EndPoint sslEndPoint = sslConnection.getDecryptedEndPoint();
            NextProtoNegoServerConnection connection = new NextProtoNegoServerConnection(channel, sslEndPoint, this);
            sslEndPoint.setConnection(connection);
            getSelectorManager().connectionOpened(connection);

            NextProtoNego.put(engine, connection);

            return sslConnection;
        }
        else
        {
            ConnectionFactory connectionFactory = getDefaultConnectionFactory();
            Connection connection = connectionFactory.newConnection(channel, endPoint, this);
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
        SSLEngine engine = sslContextFactory.newSSLEngine(peerHost, peerPort);
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

    public void replaceConnection(EndPoint endPoint, Connection connection)
    {
        Connection oldConnection = endPoint.getConnection();
        endPoint.setConnection(connection);
        getSelectorManager().connectionUpgraded(endPoint, oldConnection);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        super.dump(out, indent);
        AggregateLifeCycle.dump(out, indent, new ArrayList<>(sessions));
    }
}
