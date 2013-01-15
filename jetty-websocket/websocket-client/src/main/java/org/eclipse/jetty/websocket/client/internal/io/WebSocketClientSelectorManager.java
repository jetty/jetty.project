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

package org.eclipse.jetty.websocket.client.internal.io;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.internal.ConnectPromise;

public class WebSocketClientSelectorManager extends SelectorManager
{
    private static final Logger LOG = Log.getLogger(WebSocketClientSelectorManager.class);
    private final WebSocketPolicy policy;
    private final ByteBufferPool bufferPool;
    private SslContextFactory sslContextFactory;

    public WebSocketClientSelectorManager(ByteBufferPool bufferPool, Executor executor, Scheduler scheduler, WebSocketPolicy policy)
    {
        super(executor,scheduler);
        this.bufferPool = bufferPool;
        this.policy = policy;
    }

    @Override
    protected void connectionFailed(SocketChannel channel, Throwable ex, Object attachment)
    {
        ConnectPromise connect = (ConnectPromise)attachment;
        connect.failed(ex);
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    public Connection newConnection(final SocketChannel channel, EndPoint endPoint, final Object attachment) throws IOException
    {
        LOG.debug("newConnection({},{},{})",channel,endPoint,attachment);
        ConnectPromise connectPromise = (ConnectPromise)attachment;

        try
        {
            String scheme = connectPromise.getRequest().getRequestURI().getScheme();

            if ("wss".equalsIgnoreCase(scheme))
            {
                // Encrypted "wss://"
                SslContextFactory sslContextFactory = getSslContextFactory();
                if (sslContextFactory != null)
                {
                    SSLEngine engine = newSSLEngine(sslContextFactory,channel);
                    SslConnection sslConnection = new SslConnection(bufferPool,getExecutor(),endPoint,engine);
                    EndPoint sslEndPoint = sslConnection.getDecryptedEndPoint();

                    Connection connection = newUpgradeConnection(channel,sslEndPoint,connectPromise);
                    sslEndPoint.setConnection(connection);
                    connectionOpened(connection);
                    return sslConnection;
                }
                else
                {
                    throw new IOException("Cannot init SSL");
                }
            }
            else
            {
                // Standard "ws://"
                return newUpgradeConnection(channel,endPoint,connectPromise);
            }
        }
        catch (IOException e)
        {
            LOG.debug(e);
            connectPromise.failed(e);
            // rethrow
            throw e;
        }
    }

    @Override
    protected EndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey selectionKey) throws IOException
    {
        LOG.debug("newEndPoint({}, {}, {})",channel,selectSet,selectionKey);
        return new SelectChannelEndPoint(channel,selectSet,selectionKey,getScheduler(),policy.getIdleTimeout());
    }

    public SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SocketChannel channel)
    {
        String peerHost = channel.socket().getInetAddress().getHostAddress();
        int peerPort = channel.socket().getPort();
        SSLEngine engine = sslContextFactory.newSSLEngine(peerHost,peerPort);
        engine.setUseClientMode(true);
        return engine;
    }

    public UpgradeConnection newUpgradeConnection(SocketChannel channel, EndPoint endPoint, ConnectPromise connectPromise)
    {
        WebSocketClient client = connectPromise.getClient();
        Executor executor = client.getExecutor();
        UpgradeConnection connection = new UpgradeConnection(endPoint,executor,connectPromise);
        return connection;
    }

    public void setSslContextFactory(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
    }
}
