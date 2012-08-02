// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.client.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketClientFactory;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.eclipse.jetty.websocket.io.WebSocketAsyncConnection;

public class WebSocketClientSelectorManager extends SelectorManager
{
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final WebSocketPolicy policy;
    private final ByteBufferPool bufferPool;
    private SslContextFactory sslContextFactory;

    public WebSocketClientSelectorManager(ByteBufferPool bufferPool, Executor executor, ScheduledExecutorService scheduler, WebSocketPolicy policy)
    {
        super();
        this.bufferPool = bufferPool;
        this.executor = executor;
        this.scheduler = scheduler;
        this.policy = policy;
    }

    @Override
    protected void execute(Runnable task)
    {
        // TODO Auto-generated method stub
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    public AsyncConnection newAsyncConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
    {
        WebSocketClient.ConnectFuture confut = (WebSocketClient.ConnectFuture)attachment;
        WebSocketClientFactory factory = confut.getFactory();
        WebSocketEventDriver websocket = confut.getWebSocket();

        Executor executor = factory.getExecutor();
        WebSocketPolicy policy = factory.getPolicy();
        ByteBufferPool bufferPool = factory.getBufferPool();
        ScheduledExecutorService scheduler = factory.getScheduler();

        WebSocketAsyncConnection connection = new WebSocketClientAsyncConnection(endPoint,executor,scheduler,policy,bufferPool,factory);
        endPoint.setAsyncConnection(connection);
        connection.getParser().setIncomingFramesHandler(websocket);

        // TODO: track open websockets? bind open websocket to connection?

        return connection;
    }

    @Override
    public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endPoint, Object attachment)
    {
        WebSocketClient.ConnectFuture confut = (WebSocketClient.ConnectFuture)attachment;

        try
        {
            String scheme = confut.getWebSocketUri().getScheme();

            if ((sslContextFactory != null) && ("wss".equalsIgnoreCase(scheme)))
            {
                final AtomicReference<AsyncEndPoint> sslEndPointRef = new AtomicReference<>();
                final AtomicReference<Object> attachmentRef = new AtomicReference<>(attachment);
                SSLEngine engine = newSSLEngine(sslContextFactory,channel);
                SslConnection sslConnection = new SslConnection(bufferPool,executor,endPoint,engine)
                {
                    @Override
                    public void onClose()
                    {
                        sslEndPointRef.set(null);
                        attachmentRef.set(null);
                        super.onClose();
                    }
                };
                endPoint.setAsyncConnection(sslConnection);
                AsyncEndPoint sslEndPoint = sslConnection.getDecryptedEndPoint();
                sslEndPointRef.set(sslEndPoint);

                startHandshake(engine);

                AsyncConnection connection = newAsyncConnection(channel,sslEndPoint,attachment);
                endPoint.setAsyncConnection(connection);
                return connection;
            }
            else
            {
                AsyncConnection connection = newAsyncConnection(channel,endPoint,attachment);
                endPoint.setAsyncConnection(connection);
                return connection;
            }
        }
        catch (Throwable t)
        {
            LOG.debug(t);
            confut.failed(null,t);
            throw t;
        }
    }

    @Override
    protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey selectionKey) throws IOException
    {
        return new SelectChannelEndPoint(channel,selectSet,selectionKey,scheduler,policy.getIdleTimeout());
    }

    public SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSslEngine(peerHost,peerPort);
        engine.setUseClientMode(true);
        return engine;
    }

    public void setSslContextFactory(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
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
