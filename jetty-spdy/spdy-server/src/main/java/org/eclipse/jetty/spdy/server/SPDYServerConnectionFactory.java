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

package org.eclipse.jetty.spdy.server;

import java.util.Collection;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.spdy.CompressionFactory;
import org.eclipse.jetty.spdy.FlowControlStrategy;
import org.eclipse.jetty.spdy.StandardCompressionFactory;
import org.eclipse.jetty.spdy.StandardSession;
import org.eclipse.jetty.spdy.api.GoAwayInfo;
import org.eclipse.jetty.spdy.api.Session;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.client.FlowControlStrategyFactory;
import org.eclipse.jetty.spdy.client.SPDYConnection;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ManagedObject("SPDY Server Connection Factory")
public class SPDYServerConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = Log.getLogger(SPDYServerConnectionFactory.class);

    // This method is placed here so as to provide a check for NPN before attempting to load any
    // NPN classes.
    public static void checkNPNAvailable()
    {
        try
        {
            Class<?> npn = ClassLoader.getSystemClassLoader().loadClass("org.eclipse.jetty.npn.NextProtoNego");
            if (npn.getClassLoader() != null)
                throw new IllegalStateException("NextProtoNego must be on JVM boot path");
        }
        catch (ClassNotFoundException e)
        {
            throw new IllegalStateException("No NextProtoNego on boot path", e);
        }
    }

    private final short version;
    private final ServerSessionFrameListener listener;
    private int initialWindowSize;
    private boolean executeOnFillable = true;
    private final Queue<Session> sessions = new ConcurrentLinkedQueue<>();

    public SPDYServerConnectionFactory(int version)
    {
        this(version, null);
    }

    public SPDYServerConnectionFactory(int version, ServerSessionFrameListener listener)
    {
        super("spdy/" + version);
        this.version = (short)version;
        this.listener = listener;
        setInitialWindowSize(65536);
    }

    @ManagedAttribute("SPDY version")
    public short getVersion()
    {
        return version;
    }

    public ServerSessionFrameListener getServerSessionFrameListener()
    {
        return listener;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endPoint)
    {
        CompressionFactory compressionFactory = new StandardCompressionFactory();
        Parser parser = new Parser(compressionFactory.newDecompressor());
        Generator generator = new Generator(connector.getByteBufferPool(), compressionFactory.newCompressor());

        ServerSessionFrameListener listener = provideServerSessionFrameListener(connector, endPoint);
        SPDYConnection connection = new ServerSPDYConnection(connector, endPoint, parser, listener,
                executeOnFillable, getInputBufferSize());

        FlowControlStrategy flowControlStrategy = newFlowControlStrategy(version);

        StandardSession session = new StandardSession(getVersion(), connector.getByteBufferPool(),
                connector.getExecutor(), connector.getScheduler(), connection, endPoint, connection, 2, listener,
                generator, flowControlStrategy);
        session.setWindowSize(initialWindowSize);
        parser.addListener(session);
        connection.setSession(session);

        sessionOpened(session);

        return configure(connection, connector, endPoint);
    }

    protected FlowControlStrategy newFlowControlStrategy(short version)
    {
        return FlowControlStrategyFactory.newFlowControlStrategy(version);
    }

    protected ServerSessionFrameListener provideServerSessionFrameListener(Connector connector, EndPoint endPoint)
    {
        return listener;
    }

    @ManagedAttribute("Initial Window Size")
    public int getInitialWindowSize()
    {
        return initialWindowSize;
    }

    public void setInitialWindowSize(int initialWindowSize)
    {
        this.initialWindowSize = initialWindowSize;
    }

    @ManagedAttribute("Execute onFillable")
    public boolean isExecuteOnFillable()
    {
        return executeOnFillable;
    }

    public void setExecuteOnFillable(boolean executeOnFillable)
    {
        this.executeOnFillable = executeOnFillable;
    }

    protected boolean sessionOpened(Session session)
    {
        // Add sessions only if the connector is not stopping
        return sessions.offer(session);
    }

    protected boolean sessionClosed(Session session)
    {
        // Remove sessions only if the connector is not stopping
        // to avoid concurrent removes during iterations
        return sessions.remove(session);
    }

    void closeSessions()
    {
        for (Session session : sessions)
            session.goAway(new GoAwayInfo(), new Callback.Adapter());
        sessions.clear();
    }

    @Override
    protected void doStop() throws Exception
    {
        closeSessions();
        super.doStop();
    }

    public Collection<Session> getSessions()
    {
        return Collections.unmodifiableCollection(sessions);
    }

    private class ServerSPDYConnection extends SPDYConnection implements Runnable
    {
        private final ServerSessionFrameListener listener;
        private final AtomicBoolean connected = new AtomicBoolean();

        private ServerSPDYConnection(Connector connector, EndPoint endPoint, Parser parser,
                                     ServerSessionFrameListener listener, boolean executeOnFillable, int bufferSize)
        {
            super(endPoint, connector.getByteBufferPool(), parser, connector.getExecutor(),
                    executeOnFillable, bufferSize);
            this.listener = listener;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            if (connected.compareAndSet(false, true))
                getExecutor().execute(this);
        }

        @Override
        public void onClose()
        {
            super.onClose();
            sessionClosed(getSession());
        }

        @Override
        public void run()
        {
            // NPE guard to support tests
            if (listener != null)
                listener.onConnect(getSession());
        }
    }

}
