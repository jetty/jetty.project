//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.DeprecationWarning;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;

public class SimpleContainerScope extends ContainerLifeCycle implements WebSocketContainerScope
{
    private final ByteBufferPool bufferPool;
    private final DecoratedObjectFactory objectFactory;
    private final WebSocketPolicy policy;
    private final Executor executor;
    private final Logger logger;
    private SslContextFactory sslContextFactory;
    private List<WebSocketSessionListener> sessionListeners = new ArrayList<>();

    public SimpleContainerScope(WebSocketPolicy policy)
    {
        this(policy, new MappedByteBufferPool());
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
        this(policy, bufferPool, executor, null, objectFactory);
    }

    public SimpleContainerScope(WebSocketPolicy policy, ByteBufferPool bufferPool, Executor executor, SslContextFactory ssl, DecoratedObjectFactory objectFactory)
    {
        this.logger = Log.getLogger(this.getClass());
        this.policy = policy;
        this.bufferPool = bufferPool;

        if (objectFactory == null)
        {
            this.objectFactory = new DecoratedObjectFactory();
            this.objectFactory.addDecorator(new DeprecationWarning());
        }
        else
        {
            this.objectFactory = objectFactory;
        }

        if (ssl == null)
        {
            if (policy.getBehavior() == WebSocketBehavior.CLIENT)
                this.sslContextFactory = new SslContextFactory.Client();
            else if (policy.getBehavior() == WebSocketBehavior.SERVER)
                this.sslContextFactory = new SslContextFactory.Server();
        }
        else
        {
            this.sslContextFactory = ssl;
        }

        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            String behavior = "Container";
            if (policy != null)
            {
                if (policy.getBehavior() == WebSocketBehavior.CLIENT)
                    behavior = "Client";
                else if (policy.getBehavior() == WebSocketBehavior.SERVER)
                    behavior = "Server";
            }
            String name = String.format("WebSocket%s@%s", behavior, hashCode());
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
        return this.policy;
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
    public void addSessionListener(WebSocketSessionListener listener)
    {
        this.sessionListeners.add(listener);
    }

    @Override
    public void removeSessionListener(WebSocketSessionListener listener)
    {
        this.sessionListeners.remove(listener);
    }

    @Override
    public Collection<WebSocketSessionListener> getSessionListeners()
    {
        return sessionListeners;
    }
}
