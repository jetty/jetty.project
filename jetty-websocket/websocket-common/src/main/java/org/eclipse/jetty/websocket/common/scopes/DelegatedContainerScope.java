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

import java.util.Collection;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSessionListener;

public class DelegatedContainerScope implements WebSocketContainerScope
{
    private final WebSocketPolicy policy;
    private final WebSocketContainerScope delegate;

    public DelegatedContainerScope(WebSocketPolicy policy, WebSocketContainerScope parentScope)
    {
        this.policy = policy;
        this.delegate = parentScope;
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        return this.delegate.getBufferPool();
    }

    @Override
    public Executor getExecutor()
    {
        return this.delegate.getExecutor();
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return this.delegate.getObjectFactory();
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    @Override
    public SslContextFactory getSslContextFactory()
    {
        return this.delegate.getSslContextFactory();
    }

    @Override
    public boolean isRunning()
    {
        return this.delegate.isRunning();
    }

    @Override
    public void addSessionListener(WebSocketSessionListener listener)
    {
        this.delegate.addSessionListener(listener);
    }

    @Override
    public void removeSessionListener(WebSocketSessionListener listener)
    {
        this.delegate.removeSessionListener(listener);
    }

    @Override
    public Collection<WebSocketSessionListener> getSessionListeners()
    {
        return this.delegate.getSessionListeners();
    }
}
