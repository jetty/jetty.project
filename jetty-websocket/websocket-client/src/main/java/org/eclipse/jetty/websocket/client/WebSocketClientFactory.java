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

import java.io.IOException;
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
    /**
     * Have the factory maintain 1 and only 1 scheduler. All connections share this scheduler.
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final Queue<WebSocketConnection> connections = new ConcurrentLinkedQueue<>();
    private final ByteBufferPool bufferPool = new StandardByteBufferPool();
    private final Executor executor;
    private final WebSocketClientSelectorManager selector;
    private final EventMethodsCache methodsCache;
    private final WebSocketPolicy policy;

    public WebSocketClientFactory()
    {
        this(new QueuedThreadPool(),null);
    }

    public WebSocketClientFactory(Executor threadPool)
    {
        this(threadPool,null);
    }

    public WebSocketClientFactory(Executor executor, SslContextFactory sslContextFactory)
    {
        if (executor == null)
        {
            throw new IllegalArgumentException("Executor is required");
        }
        this.executor = executor;
        addBean(executor);

        if (sslContextFactory != null)
        {
            addBean(sslContextFactory);
        }

        selector = new WebSocketClientSelectorManager(bufferPool,executor);
        selector.setSslContextFactory(sslContextFactory);
        addBean(selector);

        this.methodsCache = new EventMethodsCache();

        this.policy = WebSocketPolicy.newClientPolicy();
    }

    public WebSocketClientFactory(SslContextFactory sslContextFactory)
    {
        this(null,sslContextFactory);
    }

    private void closeConnections()
    {
        for (WebSocketConnection connection : connections)
        {
            try
            {
                connection.close();
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
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

    public WebSocketEventDriver newWebSocketDriver(Object websocketPojo)
    {
        return new WebSocketEventDriver(websocketPojo,methodsCache,policy,getBufferPool());
    }
}
