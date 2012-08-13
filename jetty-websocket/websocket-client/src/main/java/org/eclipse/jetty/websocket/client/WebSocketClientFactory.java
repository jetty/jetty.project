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
package org.eclipse.jetty.websocket.client;

import java.util.Collection;
import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.component.AggregateLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.io.WebSocketClientSelectorManager;
import org.eclipse.jetty.websocket.driver.EventMethodsCache;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;

public class WebSocketClientFactory extends AggregateLifeCycle
{
    private static final Logger LOG = Log.getLogger(WebSocketClientFactory.class);
    private final Queue<WebSocketConnection> connections = new ConcurrentLinkedQueue<>();
    private final ByteBufferPool bufferPool = new StandardByteBufferPool();
    private final Executor executor;
    private final ScheduledExecutorService scheduler;
    private final WebSocketClientSelectorManager selector;
    private final EventMethodsCache methodsCache;
    private final WebSocketPolicy policy;

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

        selector = new WebSocketClientSelectorManager(bufferPool,executor,scheduler,policy);
        selector.setSslContextFactory(sslContextFactory);
        addBean(selector);

        this.methodsCache = new EventMethodsCache();
    }

    public WebSocketClientFactory(SslContextFactory sslContextFactory)
    {
        this(new QueuedThreadPool(),Executors.newSingleThreadScheduledExecutor(),sslContextFactory);
    }

    private void closeConnections()
    {
        for (WebSocketConnection connection : connections)
        {
            connection.close();
        }
        connections.clear();
    }

    @Override
    protected void doStop() throws Exception
    {
        closeConnections();
        super.doStop();
    }

    public ByteBufferPool getBufferPool()
    {
        return bufferPool;
    }

    protected Collection<WebSocketConnection> getConnections()
    {
        return Collections.unmodifiableCollection(connections);
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    public ScheduledExecutorService getScheduler()
    {
        return scheduler;
    }

    public SelectorManager getSelector()
    {
        return selector;
    }

    public WebSocketClient newWebSocketClient()
    {
        return new WebSocketClient(this);
    }

    protected WebSocketEventDriver newWebSocketDriver(Object websocketPojo)
    {
        return new WebSocketEventDriver(websocketPojo,methodsCache,policy,getBufferPool());
    }
}
