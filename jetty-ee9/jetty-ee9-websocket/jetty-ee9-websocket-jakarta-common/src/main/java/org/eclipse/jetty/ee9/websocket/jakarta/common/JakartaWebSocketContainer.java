//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import jakarta.websocket.Extension;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class JakartaWebSocketContainer extends ContainerLifeCycle implements jakarta.websocket.WebSocketContainer, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(JakartaWebSocketContainer.class);
    private final List<JakartaWebSocketSessionListener> sessionListeners = new ArrayList<>();
    protected final SessionTracker sessionTracker = new SessionTracker();
    protected final Configuration.ConfigurationCustomizer defaultCustomizer = new Configuration.ConfigurationCustomizer();
    protected final WebSocketComponents components;

    public JakartaWebSocketContainer(WebSocketComponents components)
    {
        this.components = components;
        addSessionListener(sessionTracker);
        addBean(sessionTracker);
    }

    public abstract Executor getExecutor();

    protected abstract JakartaWebSocketFrameHandlerFactory getFrameHandlerFactory();

    public ByteBufferPool getBufferPool()
    {
        return components.getBufferPool();
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return components.getExtensionRegistry();
    }

    public DecoratedObjectFactory getObjectFactory()
    {
        return components.getObjectFactory();
    }

    public WebSocketComponents getWebSocketComponents()
    {
        return components;
    }

    public long getDefaultAsyncSendTimeout()
    {
        return defaultCustomizer.getWriteTimeout().toMillis();
    }

    @Override
    public int getDefaultMaxBinaryMessageBufferSize()
    {
        long max = defaultCustomizer.getMaxBinaryMessageSize();
        if (max > (long)Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        return (int)max;
    }

    @Override
    public long getDefaultMaxSessionIdleTimeout()
    {
        return defaultCustomizer.getIdleTimeout().toMillis();
    }

    @Override
    public int getDefaultMaxTextMessageBufferSize()
    {
        long max = defaultCustomizer.getMaxTextMessageSize();
        if (max > (long)Integer.MAX_VALUE)
            return Integer.MAX_VALUE;
        return (int)max;
    }

    @Override
    public void setAsyncSendTimeout(long ms)
    {
        defaultCustomizer.setWriteTimeout(Duration.ofMillis(ms));
    }

    @Override
    public void setDefaultMaxBinaryMessageBufferSize(int max)
    {
        defaultCustomizer.setMaxBinaryMessageSize(max);
    }

    @Override
    public void setDefaultMaxSessionIdleTimeout(long ms)
    {
        defaultCustomizer.setIdleTimeout(Duration.ofMillis(ms));
    }

    @Override
    public void setDefaultMaxTextMessageBufferSize(int max)
    {
        defaultCustomizer.setMaxTextMessageSize(max);
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

        for (String name : getExtensionRegistry().getAvailableExtensionNames())
        {
            ret.add(new JakartaWebSocketExtension(name));
        }

        return ret;
    }

    /**
     * Used in {@link jakarta.websocket.Session#getOpenSessions()}
     *
     * @return the set of open sessions
     */
    public Set<jakarta.websocket.Session> getOpenSessions()
    {
        return sessionTracker.getSessions();
    }

    public JakartaWebSocketFrameHandler newFrameHandler(Object websocketPojo, UpgradeRequest upgradeRequest)
    {
        return getFrameHandlerFactory().newJakartaWebSocketFrameHandler(websocketPojo, upgradeRequest);
    }

    /**
     * Register a WebSocketSessionListener with the container
     *
     * @param listener the listener
     */
    public void addSessionListener(JakartaWebSocketSessionListener listener)
    {
        sessionListeners.add(listener);
    }

    /**
     * Remove a WebSocketSessionListener from the container
     *
     * @param listener the listener
     * @return true if listener was present and removed
     */
    public boolean removeSessionListener(JakartaWebSocketSessionListener listener)
    {
        return sessionListeners.remove(listener);
    }

    /**
     * Notify Session Listeners of events
     *
     * @param consumer the consumer to pass to each listener
     */
    public void notifySessionListeners(Consumer<JakartaWebSocketSessionListener> consumer)
    {
        for (JakartaWebSocketSessionListener listener : sessionListeners)
        {
            try
            {
                consumer.accept(listener);
            }
            catch (Throwable x)
            {
                LOG.info("Exception while invoking listener {}", listener, x);
            }
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(out, indent, this, defaultCustomizer);
    }
}
