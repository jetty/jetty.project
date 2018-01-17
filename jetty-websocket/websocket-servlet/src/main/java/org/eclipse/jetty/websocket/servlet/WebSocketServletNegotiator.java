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

import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

import java.io.IOException;
import java.net.URISyntaxException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public class WebSocketServletNegotiator implements WebSocketNegotiator
{
    private final WebSocketServletFactory factory;
    private final WebSocketCreator creator;
    private FrameHandlerFactory frameHandlerFactory;

    public WebSocketServletNegotiator(WebSocketServletFactory factory, WebSocketCreator creator)
    {
        this.factory = factory;
        this.creator = creator;
    }

    public WebSocketServletNegotiator(WebSocketServletFactory factory, WebSocketCreator creator, FrameHandlerFactory frameHandlerFactory)
    {
        this.factory = factory;
        this.creator = creator;
        this.frameHandlerFactory = frameHandlerFactory;
    }

    public WebSocketServletFactory getFactory()
    {
        return factory;
    }

    public WebSocketCreator getCreator()
    {
        return creator;
    }

    public FrameHandlerFactory getFrameHandlerFactory()
    {
        return frameHandlerFactory;
    }

    @Override
    public FrameHandler negotiate(Negotiation negotiation) throws IOException
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(factory.getContextClassloader());

            ServletUpgradeRequest upgradeRequest = new ServletUpgradeRequest(negotiation.getRequest());
            ServletUpgradeResponse upgradeResponse = new ServletUpgradeResponse(negotiation.getResponse());

            Object websocketPojo = creator.createWebSocket(upgradeRequest, upgradeResponse);

            // Handling for response forbidden (and similar paths)
            if(upgradeResponse.isCommitted())
            {
                return null;
            }

            if(websocketPojo == null)
            {
                // no creation, sorry
                upgradeResponse.sendError(SC_SERVICE_UNAVAILABLE, "WebSocket Endpoint Creation Refused");
                return null;
            }

            // If a FrameHandlerFactory is specified, use it
            if (frameHandlerFactory != null)
            {
                return frameHandlerFactory.newFrameHandler(websocketPojo);
            }

            // Use WebSocketServletFactory to create FrameHandler
            return factory.newFrameHandler(websocketPojo);
        }
        catch (URISyntaxException e)
        {
            throw new IOException("Unable to negotiate websocket due to mangled request URI", e);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Override
    public WebSocketPolicy getCandidatePolicy()
    {
        return factory.getPolicy();
    }

    @Override
    public WebSocketExtensionRegistry getExtensionRegistry()
    {
        return factory.getExtensionRegistry();
    }

    @Override
    public DecoratedObjectFactory getObjectFactory()
    {
        return factory.getObjectFactory();
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return factory.getBufferPool();
    }
}
