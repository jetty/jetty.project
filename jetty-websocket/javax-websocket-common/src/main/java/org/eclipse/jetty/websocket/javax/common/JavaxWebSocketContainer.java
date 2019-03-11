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

package org.eclipse.jetty.websocket.javax.common;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javax.websocket.Extension;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;

public abstract class JavaxWebSocketContainer extends ContainerLifeCycle implements javax.websocket.WebSocketContainer
{
    private final static Logger LOG = Log.getLogger(JavaxWebSocketContainer.class);
    private final SessionTracker sessionTracker = new SessionTracker();
    private long defaultAsyncSendTimeout = -1;
    private int defaultMaxBinaryMessageBufferSize = 64 * 1024;
    private int defaultMaxTextMessageBufferSize = 64 * 1024;
    private List<JavaxWebSocketSessionListener> sessionListeners = new ArrayList<>();

    public JavaxWebSocketContainer()
    {
        addSessionListener(sessionTracker);
        addBean(sessionTracker);
    }

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
        return sessionTracker.getSessions();
    }

    public JavaxWebSocketFrameHandler newFrameHandler(Object websocketPojo, UpgradeRequest upgradeRequest, UpgradeResponse upgradeResponse,
        CompletableFuture<Session> futureSession)
    {
        return getFrameHandlerFactory().newJavaxWebSocketFrameHandler(websocketPojo, upgradeRequest, upgradeResponse, futureSession);
    }

    @Override
    public void setAsyncSendTimeout(long timeoutInMillis)
    {
        this.defaultAsyncSendTimeout = timeoutInMillis;
    }

    protected abstract WebSocketExtensionRegistry getExtensionRegistry();

    protected abstract JavaxWebSocketFrameHandlerFactory getFrameHandlerFactory();

    /**
     * Register a WebSocketSessionListener with the container
     *
     * @param listener the listener
     */
    public void addSessionListener(JavaxWebSocketSessionListener listener)
    {
        sessionListeners.add(listener);
    }

    /**
     * Remove a WebSocketSessionListener from the container
     *
     * @param listener the listener
     * @return true if listener was present and removed
     */
    public boolean removeSessionListener(JavaxWebSocketSessionListener listener)
    {
        return sessionListeners.remove(listener);
    }

    /**
     * Notify Session Listeners of events
     *
     * @param consumer the consumer to pass to each listener
     */
    public void notifySessionListeners(Consumer<JavaxWebSocketSessionListener> consumer)
    {
        for (JavaxWebSocketSessionListener listener : sessionListeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener " + listener, x);
            }
        }
    }
}
