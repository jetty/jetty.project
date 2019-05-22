//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.DeprecationWarning;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;

public class SimpleContainerScope implements WebSocketContainerScope
{
    private final WebSocketPolicy policy;
    private ByteBufferPool bufferPool;
    private DecoratedObjectFactory objectFactory;
    private Executor executor;
    private SslContextFactory sslContextFactory;

    public SimpleContainerScope(WebSocketPolicy policy)
    {
        this.policy = policy;
    }

    public SimpleContainerScope setBufferPool(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
        return this;
    }

    public SimpleContainerScope setExecutor(Executor executor)
    {
        this.executor = executor;
        return this;
    }

    public SimpleContainerScope setObjectFactory(DecoratedObjectFactory objectFactory)
    {
        this.objectFactory = objectFactory;
        return this;
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        if (this.bufferPool == null)
        {
            this.bufferPool = new MappedByteBufferPool();
        }

        return this.bufferPool;
    }

    @Override
    public Executor getExecutor()
    {
        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            String behavior = "Container";
            if (policy != null)
            {
                if (policy.getBehavior() == WebSocketBehavior.CLIENT)
                {
                    behavior = "Client";
                }
                else if (policy.getBehavior() == WebSocketBehavior.SERVER)
                {
                    behavior = "Server";
                }
            }
            String name = String.format("WebSocket%s@%s", behavior, hashCode());
            threadPool.setName(name);
            threadPool.setDaemon(true);
            this.executor = threadPool;
        }
        return this.executor;
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        this.objectFactory = new DecoratedObjectFactory();
        this.objectFactory.addDecorator(new DeprecationWarning());
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
        if(this.sslContextFactory == null)
        {
            if (policy.getBehavior() == WebSocketBehavior.CLIENT)
            {
                this.sslContextFactory = new SslContextFactory.Client();
            }
            else if (policy.getBehavior() == WebSocketBehavior.SERVER)
            {
                this.sslContextFactory = new SslContextFactory.Server();
            }
        }
        return this.sslContextFactory;
    }

    public SimpleContainerScope setSslContextFactory(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        return this;
    }

    @Override
    public void notifySessionListeners(Consumer<WebSocketSessionListener> eventConsumer)
    {
        // do nothing, this is a test/mock instance.
    }
}
