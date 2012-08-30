//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.spdy;

import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;

public class ServerSPDYConnectionFactory implements ConnectionFactory
{
    private final ByteBufferPool bufferPool;
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final short version;
    private final ServerSessionFrameListener listener;

    public ServerSPDYConnectionFactory(short version, ByteBufferPool bufferPool, Executor executor, ScheduledExecutorService scheduler)
    {
        this(version, bufferPool, executor, scheduler, null);
    }

    public ServerSPDYConnectionFactory(short version, ByteBufferPool bufferPool, Executor executor, ScheduledExecutorService scheduler, ServerSessionFrameListener listener)
    {
        this.version = version;
        this.bufferPool = bufferPool;
        this.executor = executor;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    public short getVersion()
    {
        return version;
    }

    @Override
    public Connection newConnection(SocketChannel channel, EndPoint endPoint, Object attachment)
    {
        CompressionFactory compressionFactory = new StandardCompressionFactory();
        Parser parser = new Parser(compressionFactory.newDecompressor());
        Generator generator = new Generator(bufferPool, compressionFactory.newCompressor());

        SPDYServerConnector connector = (SPDYServerConnector)attachment;

        ServerSessionFrameListener listener = provideServerSessionFrameListener(endPoint, attachment);
        SPDYConnection connection = new ServerSPDYConnection(endPoint, bufferPool, parser, listener, connector);

        FlowControlStrategy flowControlStrategy = connector.newFlowControlStrategy(version);

        StandardSession session = new StandardSession(getVersion(), getBufferPool(), getExecutor(), getScheduler(), connection, connection, 2, listener, generator, flowControlStrategy);
        session.setAttribute("org.eclipse.jetty.spdy.remoteAddress", endPoint.getRemoteAddress()); // TODO: make this available through API
        session.setWindowSize(connector.getInitialWindowSize());
        parser.addListener(session);
        connection.setSession(session);

        connector.sessionOpened(session);

        return connection;
    }

    protected ServerSessionFrameListener provideServerSessionFrameListener(EndPoint endPoint, Object attachment)
    {
        return listener;
    }

    protected ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    protected Executor getExecutor()
    {
        return executor;
    }

    public ScheduledExecutorService getScheduler()
    {
        return scheduler;
    }

    private static class ServerSPDYConnection extends SPDYConnection implements Runnable
    {
        private final ServerSessionFrameListener listener;
        private final SPDYServerConnector connector;
        private final AtomicBoolean connected = new AtomicBoolean();

        private ServerSPDYConnection(EndPoint endPoint, ByteBufferPool bufferPool, Parser parser, ServerSessionFrameListener listener, SPDYServerConnector connector)
        {
            super(endPoint, bufferPool, parser, connector.getExecutor());
            this.listener = listener;
            this.connector = connector;
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
            connector.sessionClosed(getSession());
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
