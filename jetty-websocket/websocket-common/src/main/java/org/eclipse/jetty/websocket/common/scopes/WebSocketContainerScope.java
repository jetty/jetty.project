//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.WebSocketSession;

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
     * Test for if the container has been started.
     *
     * @return true if container is started and running
     */
    boolean isRunning();
    
    /**
     * A Session has been opened
     * 
     * @param session the session that was opened
     */
    void onSessionOpened(WebSocketSession session);
    
    /**
     * A Session has been closed
     * 
     * @param session the session that was closed
     */
    void onSessionClosed(WebSocketSession session);
}
