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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;

/**
 * A simple Scope that is focused around a HttpClient, DecoratedObjectFactory, and Client WebSocketPolicy.
 */
public class HttpContainerScope extends ContainerLifeCycle implements WebSocketContainerScope
{
    private final HttpClient httpClient;
    private final DecoratedObjectFactory decoratedObjectFactory;
    private final WebSocketPolicy webSocketPolicy;
    private List<WebSocketSessionListener> sessionListeners = new ArrayList<>();

    public HttpContainerScope(HttpClient httpClient)
    {
        this(httpClient, new DecoratedObjectFactory());
    }

    public HttpContainerScope(HttpClient httpClient, DecoratedObjectFactory decoratedObjectFactory)
    {
        this.httpClient = Objects.requireNonNull(httpClient, "HttpClient");
        this.decoratedObjectFactory = decoratedObjectFactory != null ? decoratedObjectFactory : new DecoratedObjectFactory();
        this.webSocketPolicy = WebSocketPolicy.newClientPolicy();
    }

    public HttpContainerScope(SslContextFactory sslContextFactory, Executor executor, ByteBufferPool bufferPool, DecoratedObjectFactory decoratedObjectFactory)
    {
        this.httpClient = new HttpClient(sslContextFactory);
        this.httpClient.setExecutor(executor);
        this.httpClient.setByteBufferPool(bufferPool);
        this.decoratedObjectFactory = decoratedObjectFactory != null ? decoratedObjectFactory : new DecoratedObjectFactory();
        this.webSocketPolicy = WebSocketPolicy.newClientPolicy();
    }

    public HttpClient getHttpClient()
    {
        return httpClient;
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return httpClient.getByteBufferPool();
    }

    @Override
    public Executor getExecutor()
    {
        return httpClient.getExecutor();
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return decoratedObjectFactory;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return webSocketPolicy;
    }

    @Override
    public SslContextFactory getSslContextFactory()
    {
        return httpClient.getSslContextFactory();
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
