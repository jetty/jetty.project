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


package org.eclipse.jetty.spdy;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.spdy.generator.Generator;
import org.eclipse.jetty.spdy.parser.Parser;

public class ServerSPDYAsyncConnectionFactory implements AsyncConnectionFactory
{
    private final ByteBufferPool bufferPool;
    private final Executor threadPool;
    private final ScheduledExecutorService scheduler;
    private final short version;
    private final ServerSessionFrameListener listener;

    public ServerSPDYAsyncConnectionFactory(short version, ByteBufferPool bufferPool, Executor threadPool, ScheduledExecutorService scheduler)
    {
        this(version, bufferPool, threadPool, scheduler, null);
    }

    public ServerSPDYAsyncConnectionFactory(short version, ByteBufferPool bufferPool, Executor threadPool, ScheduledExecutorService scheduler, ServerSessionFrameListener listener)
    {
        this.version = version;
        this.bufferPool = bufferPool;
        this.threadPool = threadPool;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    public short getVersion()
    {
        return version;
    }

    @Override
    public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
    {
        CompressionFactory compressionFactory = new StandardCompressionFactory();
        Parser parser = new Parser(compressionFactory.newDecompressor());
        Generator generator = new Generator(bufferPool, compressionFactory.newCompressor());

        SPDYServerConnector connector = (SPDYServerConnector)attachment;

        ServerSessionFrameListener listener = provideServerSessionFrameListener(endPoint, attachment);
        SPDYAsyncConnection connection = new ServerSPDYAsyncConnection(endPoint, bufferPool, parser, listener, connector);
        endPoint.setConnection(connection);

        FlowControlStrategy flowControlStrategy = connector.newFlowControlStrategy(version);

        StandardSession session = new StandardSession(version, bufferPool, threadPool, scheduler, connection, connection, 2, listener, generator, flowControlStrategy);
        session.setAttribute("org.eclipse.jetty.spdy.remoteAddress", endPoint.getRemoteAddr());
        session.setWindowSize(connector.getInitialWindowSize());
        parser.addListener(session);
        connection.setSession(session);

        connector.sessionOpened(session);

        return connection;
    }

    protected ServerSessionFrameListener provideServerSessionFrameListener(AsyncEndPoint endPoint, Object attachment)
    {
        return listener;
    }

    private static class ServerSPDYAsyncConnection extends SPDYAsyncConnection
    {
        private final ServerSessionFrameListener listener;
        private final SPDYServerConnector connector;
        private volatile boolean connected;

        private ServerSPDYAsyncConnection(AsyncEndPoint endPoint, ByteBufferPool bufferPool, Parser parser, ServerSessionFrameListener listener, SPDYServerConnector connector)
        {
            super(endPoint, bufferPool, parser);
            this.listener = listener;
            this.connector = connector;
        }

        @Override
        public Connection handle() throws IOException
        {
            if (!connected)
            {
                // NPE guard to support tests
                if (listener != null)
                    listener.onConnect(getSession());
                connected = true;
            }
            return super.handle();
        }

        @Override
        public void onClose()
        {
            super.onClose();
            connector.sessionClosed(getSession());
        }
    }
}
