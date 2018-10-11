//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

import javax.websocket.Extension;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public abstract class JavaxWebSocketContainer extends ContainerLifeCycle implements javax.websocket.WebSocketContainer
{
    private long defaultAsyncSendTimeout = -1;
    private int defaultMaxBinaryMessageBufferSize = 64 * 1024;
    private int defaultMaxTextMessageBufferSize = 64 * 1024;

    public abstract ByteBufferPool getBufferPool();

    @Override
    public long getDefaultAsyncSendTimeout()
    {
        return this.defaultAsyncSendTimeout;
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        return this.defaultMaxBinaryMessageBufferSize;
    }

    public abstract DecoratedObjectFactory getObjectFactory();

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        this.defaultMaxBinaryMessageBufferSize = max;
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        return this.defaultMaxTextMessageBufferSize;
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        this.defaultMaxTextMessageBufferSize = max;
    }

    public abstract Executor getExecutor();

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#getInstalledExtensions()
     * @since JSR356 v1.0
     */
    @Override
    public Set<Extension> getInstalledExtensions()
    {
        Set<Extension> ret = new HashSet<>();

        for (String name : getExtensionRegistry().getAvailableExtensionNames())
        {
            ret.add(new JavaxWebSocketExtension(name));
        }

        return ret;
    }

    /**
     * Used in {@link javax.websocket.Session#getOpenSessions()}
     *
     * @return the set of open sessions
     */
    public Set<javax.websocket.Session> getOpenSessions()
    {
        return new HashSet<>(getBeans(JavaxWebSocketSession.class));
    }

    public JavaxWebSocketFrameHandler newFrameHandler(Object websocketPojo, UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse, CompletableFuture<Session> futureSession)
    {
        return getFrameHandlerFactory().newJavaxFrameHandler(websocketPojo, upgradeRequest, upgradeResponse, futureSession);
    }

    @Override
    public void setAsyncSendTimeout(long timeoutInMillis)
    {
        this.defaultAsyncSendTimeout = timeoutInMillis;
    }

    protected abstract WebSocketExtensionRegistry getExtensionRegistry();

    protected abstract JavaxWebSocketFrameHandlerFactory getFrameHandlerFactory();
}
