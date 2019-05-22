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

package org.eclipse.jetty.websocket.client;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.DeprecationWarning;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

public class ClientContainerScope implements WebSocketContainerScope
{
    private final WebSocketPolicy policy;
    private ByteBufferPool bufferPool;
    private Executor executor;
    private SslContextFactory sslContextFactory;
    private DecoratedObjectFactory objectFactory;

    public ClientContainerScope()
    {
        this(WebSocketPolicy.newClientPolicy());
    }

    public ClientContainerScope(WebSocketPolicy policy)
    {
        if (policy.getBehavior() != WebSocketBehavior.CLIENT)
            throw new IllegalArgumentException("Must be a CLIENT policy");

        this.policy = policy;
    }

    public ClientContainerScope(HttpClient httpClient)
    {
        this.policy = WebSocketPolicy.newClientPolicy();
        if (httpClient != null)
        {
            this.bufferPool = httpClient.getByteBufferPool();
            this.executor = httpClient.getExecutor();
            this.sslContextFactory = httpClient.getSslContextFactory();
        }
    }

    public ClientContainerScope setBufferPool(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
        return this;
    }

    public ClientContainerScope setExecutor(Executor executor)
    {
        this.executor = executor;
        return this;
    }

    public ClientContainerScope setObjectFactory(DecoratedObjectFactory objectFactory)
    {
        this.objectFactory = objectFactory;
        return this;
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this.bufferPool;
    }

    @Override
    public Executor getExecutor()
    {
        if (executor == null)
        {
            QueuedThreadPool threadPool = new QueuedThreadPool();
            String name = String.format("WebSocketClient@%s", hashCode());
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
        return this.sslContextFactory;
    }

    public ClientContainerScope setSslContextFactory(SslContextFactory sslContextFactory)
    {
        this.sslContextFactory = sslContextFactory;
        return this;
    }

    @Override
    public void notifySessionListeners(Consumer<WebSocketSessionListener> eventConsumer)
    {
        // Using this scope is not correct, use the WebSocketClient directly
        throw new UnsupportedOperationException("Use WebSocketClient directly");
    }
}
