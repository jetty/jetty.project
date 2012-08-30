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
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.api.ExtensionRegistry;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.internal.ConnectionManager;
import org.eclipse.jetty.websocket.client.internal.IWebSocketClient;
import org.eclipse.jetty.websocket.driver.EventMethodsCache;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.eclipse.jetty.websocket.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.io.WebSocketSession;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

public class WebSocketClientFactory extends AggregateLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketClientFactory.class);

    private final ByteBufferPool bufferPool = new MappedByteBufferPool();
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final EventMethodsCache methodsCache;
    private final WebSocketPolicy policy;
    private final WebSocketExtensionRegistry extensionRegistry;
    private SocketAddress bindAddress;

    private final Queue<WebSocketSession> sessions = new ConcurrentLinkedQueue<>();
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
        LOG.debug("new WebSocketClientFactory()");
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

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
        LOG.debug("doStart()");
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
        LOG.debug("doStop()");
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

    public List<Extension> initExtensions(List<ExtensionConfig> requested)
    {
        List<Extension> extensions = new ArrayList<Extension>();

        for (ExtensionConfig cfg : requested)
        {
            Extension extension = extensionRegistry.newInstance(cfg);

            if (extension == null)
            {
                continue;
            }

            LOG.debug("added {}",extension);
            extensions.add(extension);
        }
        LOG.debug("extensions={}",extensions);
        return extensions;
    }

    public WebSocketClient newWebSocketClient(Object websocketPojo)
    {
        LOG.debug("Creating new WebSocket for {}",websocketPojo);
        WebSocketEventDriver websocket = new WebSocketEventDriver(websocketPojo,methodsCache,policy,getBufferPool());
        return new IWebSocketClient(this,websocket);
    }

    public boolean sessionClosed(WebSocketSession session)
    {
        return isRunning() && sessions.remove(session);
    }

    public boolean sessionOpened(WebSocketSession session)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Session Opened: {}",session);
        }
        // FIXME: what is going on?
        // if (!isRunning())
        // {
        // LOG.debug("Factory.isRunning: {}",this.isRunning());
        // LOG.debug("Factory.isStarted: {}",this.isStarted());
        // LOG.debug("Factory.isStarting: {}",this.isStarting());
        // LOG.debug("Factory.isStopped: {}",this.isStopped());
        // LOG.debug("Factory.isStopping: {}",this.isStopping());
        // LOG.warn("Factory is not running");
        // return false;
        // }
        boolean ret = sessions.offer(session);
        session.onConnect();
        return ret;
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
