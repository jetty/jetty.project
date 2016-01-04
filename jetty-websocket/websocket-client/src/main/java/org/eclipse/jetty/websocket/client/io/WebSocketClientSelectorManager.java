//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client.io;

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.concurrent.Executor;

import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.WebSocketClient;

public class WebSocketClientSelectorManager extends SelectorManager
{
    private static final Logger LOG = Log.getLogger(WebSocketClientSelectorManager.class);
    private final WebSocketPolicy policy;
    private final ByteBufferPool bufferPool;
    private SslContextFactory sslContextFactory;

    public WebSocketClientSelectorManager(WebSocketClient client)
    {
        super(client.getExecutor(),client.getScheduler());
        this.bufferPool = client.getBufferPool();
        this.policy = client.getPolicy();
    }

    @Override
    protected void connectionFailed(SelectableChannel channel, Throwable ex, Object attachment)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Connection Failed",ex);
        ConnectPromise connect = (ConnectPromise)attachment;
        connect.failed(ex);
    }

    public SslContextFactory getSslContextFactory()
    {
        return sslContextFactory;
    }

    @Override
    public Connection newConnection(final SelectableChannel channel, EndPoint endPoint, final Object attachment) throws IOException
    {
        if (LOG.isDebugEnabled())
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
                    sslConnection.setRenegotiationAllowed(sslContextFactory.isRenegotiationAllowed());
                    EndPoint sslEndPoint = sslConnection.getDecryptedEndPoint();

                    Connection connection = newUpgradeConnection(channel,sslEndPoint,connectPromise);
                    sslEndPoint.setIdleTimeout(connectPromise.getClient().getMaxIdleTimeout());
                    sslEndPoint.setConnection(connection);
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
                endPoint.setIdleTimeout(connectPromise.getDriver().getPolicy().getIdleTimeout());
                return newUpgradeConnection(channel,endPoint,connectPromise);
            }
        }
        catch (IOException e)
        {
            LOG.ignore(e);
            connectPromise.failed(e);
            // rethrow
            throw e;
        }
    }


    @Override
    protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("newEndPoint({}, {}, {})",channel,selector,selectionKey);
        SocketChannelEndPoint endp = new SocketChannelEndPoint(channel, selector, selectionKey, getScheduler());
        endp.setIdleTimeout(policy.getIdleTimeout());
        return endp;
    }

    public SSLEngine newSSLEngine(SslContextFactory sslContextFactory, SelectableChannel channel)
    {
        String peerHost = null;
        int peerPort = 0;
        if (channel instanceof SocketChannel)
        {
            SocketChannel sc = (SocketChannel)channel;
            peerHost = sc.socket().getInetAddress().getHostName();
            peerPort = sc.socket().getPort();
        }
        SSLEngine engine = sslContextFactory.newSSLEngine(peerHost,peerPort);
        engine.setUseClientMode(true);
        return engine;
    }

    public UpgradeConnection newUpgradeConnection(SelectableChannel channel, EndPoint endPoint, ConnectPromise connectPromise)
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
    
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }
}
