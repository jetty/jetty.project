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

package org.eclipse.jetty.websocket.servlet;

import java.util.Objects;

import org.eclipse.jetty.websocket.common.HandshakeRequest;
import org.eclipse.jetty.websocket.common.HandshakeResponse;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketException;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;

/**
 * Basic WebSocketServletFactory for working with Jetty-based WebSocketServlets
 */
public class WebSocketServletFactory
{
    private final ServletContextWebSocketContainer container;
    private final WebSocketPolicy policy;
    private final WebSocketExtensionRegistry extensionRegistry;
    private WebSocketCreator creator;

    public WebSocketServletFactory(ServletContextWebSocketContainer wsContainer)
    {
        this.container = wsContainer;
        this.policy = wsContainer.getPolicy().clonePolicyAs(WebSocketBehavior.SERVER);
        this.extensionRegistry = new WebSocketExtensionRegistry();
    }

    public ServletContextWebSocketContainer getContainer()
    {
        return container;
    }

    public WebSocketCreator getCreator()
    {
        return creator;
    }

    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return this.extensionRegistry;
    }

    /**
     * Get the base policy in use for WebSockets for this Factory.
     *
     * @return the base policy
     */
    public WebSocketPolicy getPolicy()
    {
        return this.policy;
    }

    public FrameHandler newFrameHandler(Object websocketPojo, WebSocketPolicy policy, HandshakeRequest handshakeRequest, HandshakeResponse handshakeResponse)
    {
        return container.newFrameHandler(websocketPojo, policy, handshakeRequest, handshakeResponse);
    }

    /**
     * Register a websocket class pojo with the default {@link WebSocketCreator}.
     * <p>
     * Note: only required if using the default {@link WebSocketCreator} provided by this factory.
     *
     * @param websocketPojo the class to instantiate for each incoming websocket upgrade request.
     */
    public void register(Class<?> websocketPojo)
    {
        Objects.requireNonNull(websocketPojo, "WebSocket Class cannot be null");

        setCreator((req, resp) -> {
            try
            {
                return container.getObjectFactory().createInstance(websocketPojo);
            }
            catch (InstantiationException | IllegalAccessException e)
            {
                throw new WebSocketException("Unable to create instance of " + websocketPojo, e);
            }
        });
    }

    public void setCreator(WebSocketCreator creator)
    {
        // This is called from WebSocketServlet and WebSocketUpgradeFilter
        Objects.requireNonNull(creator, "WebSocketCreator cannot be null");
        this.creator = creator;
    }
}
