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

package org.eclipse.jetty.websocket.client;

import java.net.SocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.ExtensionRegistry;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.internal.ConnectionManager;
import org.eclipse.jetty.websocket.client.internal.IWebSocketClient;
import org.eclipse.jetty.websocket.driver.EventMethodsCache;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.eclipse.jetty.websocket.extensions.WebSocketExtensionRegistry;

public class WebSocketClientFactory extends AggregateLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketClientFactory.class);

    private final ByteBufferPool bufferPool = new StandardByteBufferPool();
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final EventMethodsCache methodsCache;
    private final WebSocketPolicy policy;
    private final ExtensionRegistry extensionRegistry;
    private SocketAddress bindAddress;

    private ConnectionManager connectionManager;

    public WebSocketClientFactory()
    {
        this(new QueuedThreadPool());
    }

    public WebSocketClientFactory(Executor threadPool)
    {
        this(threadPool,Executors.newSingleThreadScheduledExecutor());
    }

    public WebSocketClientFactory(Executor threadPool, ScheduledExecutorService scheduler)
    {
        this(threadPool,scheduler,null);
    }

    public WebSocketClientFactory(Executor executor, ScheduledExecutorService scheduler, SslContextFactory sslContextFactory)
    {
        if (executor == null)
        {
            throw new IllegalArgumentException("Executor is required");
        }
        this.executor = executor;
        addBean(executor);

        if (scheduler == null)
        {
            throw new IllegalArgumentException("Scheduler is required");
        }
        this.scheduler = scheduler;

        if (sslContextFactory != null)
        {
            addBean(sslContextFactory);
        }

        this.policy = WebSocketPolicy.newClientPolicy();
        this.extensionRegistry = new WebSocketExtensionRegistry(policy,bufferPool);

        this.connectionManager = new ConnectionManager(bufferPool,executor,scheduler,sslContextFactory,policy);
        addBean(this.connectionManager);

        this.methodsCache = new EventMethodsCache();
    }

    public WebSocketClientFactory(SslContextFactory sslContextFactory)
    {
        this(new QueuedThreadPool(),Executors.newSingleThreadScheduledExecutor(),sslContextFactory);
    }

    /**
     * The address to bind local physical (outgoing) TCP Sockets to.
     * 
     * @return the address to bind the socket channel to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    public ConnectionManager getConnectionManager()
    {
        return connectionManager;
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public ExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public ScheduledExecutorService getScheduler()
    {
        return scheduler;
    }

    public WebSocketClient newWebSocketClient(Object websocketPojo)
    {
        LOG.debug("Creating new WebSocket for {}",websocketPojo);
        WebSocketEventDriver websocket = new WebSocketEventDriver(websocketPojo,methodsCache,policy,getBufferPool());
        return new IWebSocketClient(this,websocket);
    }

    /**
     * @param bindAddress
     *            the address to bind the socket channel to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }
}
