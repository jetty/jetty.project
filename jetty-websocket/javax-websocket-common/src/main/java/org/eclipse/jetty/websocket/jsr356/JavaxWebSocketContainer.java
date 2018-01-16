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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.websocket.Extension;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

public abstract class JavaxWebSocketContainer extends ContainerLifeCycle implements javax.websocket.WebSocketContainer
{
    private final WebSocketPolicy containerPolicy;
    private final WebSocketExtensionRegistry extensionRegistry;
    private final JavaxWebSocketFrameHandlerFactory localEndpointFactory;
    private long asyncSendTimeout = -1;
    private long defaultMaxSessionIdleTimeout = -1;

    public JavaxWebSocketContainer(WebSocketPolicy containerPolicy)
    {
        this.containerPolicy = containerPolicy;
        this.extensionRegistry = new WebSocketExtensionRegistry();
        this.localEndpointFactory = new JavaxWebSocketFrameHandlerFactory();
    }

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#getDefaultAsyncSendTimeout()
     * @since JSR356 v1.0
     */
    @Override
    public long getDefaultAsyncSendTimeout()
    {
        return this.asyncSendTimeout;
    }

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#getDefaultMaxBinaryMessageBufferSize()
     * @since JSR356 v1.0
     */
    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        // We use policy.maxBinaryMessageSize here, as that is what JSR356 expects
        return containerPolicy.getMaxBinaryMessageSize();
    }

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#setDefaultMaxBinaryMessageBufferSize(int)
     * @since JSR356 v1.0
     */
    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        // We use policy.maxBinaryMessageSize here, as that is what JSR356 expects
        containerPolicy.setMaxBinaryMessageSize(max);
    }

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#getDefaultMaxSessionIdleTimeout()
     * @since JSR356 v1.0
     */
    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return this.defaultMaxSessionIdleTimeout;
    }

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#setDefaultMaxSessionIdleTimeout(long)
     * @since JSR356 v1.0
     */
    @Override
    public void setDefaultMaxSessionIdleTimeout(long timeout)
    {
        this.defaultMaxSessionIdleTimeout = timeout;
    }

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#getDefaultMaxTextMessageBufferSize()
     * @since JSR356 v1.0
     */
    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        // We use policy.maxTextMessageSize here, as that is what JSR356 expects
        return containerPolicy.getMaxTextMessageSize();
    }

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#setDefaultMaxTextMessageBufferSize(int)
     * @since JSR356 v1.0
     */
    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        // We use policy.maxTextMessageSize here, as that is what JSR356 expects
        containerPolicy.setMaxTextMessageSize(max);
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return extensionRegistry;
    }

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

        for (String name : extensionRegistry.getExtensionNames())
        {
            ret.add(new JavaxWebSocketExtension(name));
        }

        return ret;
    }

    public JavaxWebSocketFrameHandlerFactory getLocalEndpointFactory()
    {
        return localEndpointFactory;
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

    public WebSocketPolicy getPolicy()
    {
        return containerPolicy;
    }

    public void removeSession(JavaxWebSocketSession session) throws Exception
    {
        if (removeBean(session))
        {
            session.stop();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see WebSocketContainer#setAsyncSendTimeout(long)
     * @since JSR356 v1.0
     */
    @Override
    public void setAsyncSendTimeout(long timeoutmillis)
    {
        this.asyncSendTimeout = timeoutmillis;
    }

    public abstract ByteBufferPool getBufferPool();

    public abstract Executor getExecutor();
}
