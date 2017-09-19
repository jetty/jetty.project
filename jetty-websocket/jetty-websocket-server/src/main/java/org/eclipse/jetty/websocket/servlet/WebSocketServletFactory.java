//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.servlet;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.core.WSPolicy;
import org.eclipse.jetty.websocket.core.extensions.WSExtensionRegistry;

/**
 * Basic WebSocketServletFactory for working with Jetty-based WebSocketServlets
 */
public interface WebSocketServletFactory
{
    boolean acceptWebSocket(HttpServletRequest request, HttpServletResponse response) throws IOException;
    
    boolean acceptWebSocket(WebSocketCreator creator, HttpServletRequest request, HttpServletResponse response) throws IOException;
    
    void start() throws Exception;
    void stop() throws Exception;
    
    WebSocketCreator getCreator();
    
    WSExtensionRegistry getExtensionRegistry();
    
    /**
     * Get the base policy in use for WebSockets.
     * <p>
     * Note: individual WebSocket implementations can override some of the values in here by using the {@link WebSocket &#064;WebSocket} annotation.
     *
     * @return the base policy
     */
    WSPolicy getPolicy();
    
    boolean isUpgradeRequest(HttpServletRequest request, HttpServletResponse response);
    
    /**
     * Register a websocket class pojo with the default {@link WebSocketCreator}.
     * <p>
     * Note: only required if using the default {@link WebSocketCreator} provided by this factory.
     *
     * @param websocketPojo the class to instantiate for each incoming websocket upgrade request.
     */
    void register(Class<?> websocketPojo);
    
    void setCreator(WebSocketCreator creator);
}
