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

/**
 * Defined Scope for a WebSocketContainer.
 */
public interface WebSocketContainerScope
{
    /**
     * The configured Container Buffer Pool.
     *
     * @return the buffer pool (never null)
     */
    ByteBufferPool getBufferPool();

    /**
     * Executor in use by the container.
     *
     * @return the Executor in use by the container.
     */
    Executor getExecutor();

    /**
     * Object Factory used to create objects.
     *
     * @return Object Factory used to create instances of objects.
     */
    DecoratedObjectFactory getObjectFactory();

    /**
     * The policy the container is running on.
     *
     * @return the websocket policy
     */
    WebSocketPolicy getPolicy();

    /**
     * The SslContextFactory in use by the container.
     *
     * @return the SslContextFactory in use by the container (can be null if no SSL context is defined)
     */
    SslContextFactory getSslContextFactory();

    /**
     * <p>The ClassLoader used to load classes for the WebSocketSession.</p>
     * <p>By default this will be the ContextClassLoader at the time this method is called. However this will be overridden
     * by the WebSocketClient to use the ContextClassLoader at the time it was created, this is because the
     * client uses its own {@link org.eclipse.jetty.util.thread.ThreadPool} so the WebSocketSessions may be created when
     * the ContextClassLoader is not set.</p>
     *
     * @return the classloader.
     */
    default ClassLoader getClassLoader()
    {
        return Thread.currentThread().getContextClassLoader();
    }

    /**
     * Test for if the container has been started.
     *
     * @return true if container is started and running
     */
    boolean isRunning();

    void addSessionListener(WebSocketSessionListener listener);

    void removeSessionListener(WebSocketSessionListener listener);

    Collection<WebSocketSessionListener> getSessionListeners();
}
