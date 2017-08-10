//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.scopes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.WebSocketSession;

public class SimpleContainerScope extends ContainerLifeCycle implements WebSocketContainerScope
{
    private final ByteBufferPool bufferPool;
    private final DecoratedObjectFactory objectFactory;
    private final WebSocketPolicy containerPolicy;
    private final Executor executor;
    protected final List<WebSocketSession.Listener> listeners = new CopyOnWriteArrayList<>();
    private SslContextFactory sslContextFactory;

    public SimpleContainerScope(WebSocketPolicy policy)
    {
        this(policy, new MappedByteBufferPool(), new DecoratedObjectFactory());
        this.sslContextFactory = new SslContextFactory();
    }

    public SimpleContainerScope(WebSocketPolicy policy, ByteBufferPool bufferPool)
    {
        this(policy, bufferPool, new DecoratedObjectFactory());
    }

    public SimpleContainerScope(WebSocketPolicy policy, ByteBufferPool bufferPool, DecoratedObjectFactory objectFactory)
    {
        this(policy, bufferPool, null, objectFactory);
    }

    public SimpleContainerScope(WebSocketPolicy policy, ByteBufferPool bufferPool, Executor executor, DecoratedObjectFactory objectFactory)
    {
        this.containerPolicy = policy;
        this.bufferPool = bufferPool;
        if (objectFactory == null)
        {
            this.objectFactory = new DecoratedObjectFactory();
        }
        else
        {
            this.objectFactory = objectFactory;
        }

        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            String name = "WebSocketContainer@" + hashCode();
            threadPool.setName(name);
            threadPool.setDaemon(true);
            this.executor = threadPool;
            addBean(this.executor);
        }
        else
        {
            this.executor = executor;
        }
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this.bufferPool;
    }

    @Override
    public Executor getExecutor()
    {
        return this.executor;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return this.objectFactory;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return this.containerPolicy;
    }

    @Override
    public SslContextFactory getSslContextFactory()
    {
        return this.sslContextFactory;
    }

    public void setSslContextFactory(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
    }

    @Override
    public void addSessionListener(WebSocketSession.Listener listener)
    {
        this.listeners.add(listener);
    }

    @Override
    public boolean removeSessionListener(WebSocketSession.Listener listener)
    {
        return this.listeners.remove(listener);
    }

    @Override
    public void onSessionOpened(WebSocketSession session)
    {
        /* do nothing */
    }

    @Override
    public void onSessionClosed(WebSocketSession session)
    {
        /* do nothing */
    }
}
