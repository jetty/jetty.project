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

package org.eclipse.jetty.websocket.servlet;

/**
 * Abstract WebSocket creator interface.
 * <p>
 * Should you desire filtering of the WebSocket object creation due to criteria such as origin or sub-protocol, then you will be required to implement a custom
 * WebSocketCreator implementation.
 * <p>
 * This has been moved from the WebSocketServlet to a standalone class managed by the WebSocketServerFactory due to need of WebSocket {@link org.eclipse.jetty.websocket.api.extensions.Extension}s that
 * require the ability to create new websockets (such as the mux extension)
 */
public interface WebSocketCreator
{
    /**
     * Create a websocket from the incoming request.
     * 
     * @param req
     *            the request details
     * @param resp
     *            the response details
     * @return a websocket object to use, or null if no websocket should be created from this request.
     */
    Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp);
}
