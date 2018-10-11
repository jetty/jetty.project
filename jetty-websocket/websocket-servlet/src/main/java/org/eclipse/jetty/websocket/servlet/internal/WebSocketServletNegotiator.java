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

package org.eclipse.jetty.websocket.servlet.internal;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFrameHandlerFactory;

import java.io.IOException;
import java.net.URISyntaxException;

import static javax.servlet.http.HttpServletResponse.SC_SERVICE_UNAVAILABLE;

public class WebSocketServletNegotiator implements WebSocketNegotiator
{
    private final WebSocketServletFactoryImpl factory;
    private final WebSocketCreator creator;
    private final WebSocketServletFrameHandlerFactory frameHandlerFactory;

    public WebSocketServletNegotiator(WebSocketServletFactoryImpl factory, WebSocketCreator creator, WebSocketServletFrameHandlerFactory frameHandlerFactory)
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

    @Override
    public FrameHandler negotiate(Negotiation negotiation) throws IOException
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(factory.getContextClassloader());

            ServletUpgradeRequest upgradeRequest = new ServletUpgradeRequest(negotiation);
            ServletUpgradeResponse upgradeResponse = new ServletUpgradeResponse(negotiation);

            Object websocketPojo = creator.createWebSocket(upgradeRequest, upgradeResponse);

            // Handling for response forbidden (and similar paths)
            if (upgradeResponse.isCommitted())
            {
                return null;
            }

            if (websocketPojo == null)
            {
                // no creation, sorry
                upgradeResponse.sendError(SC_SERVICE_UNAVAILABLE, "WebSocket Endpoint Creation Refused");
                return null;
            }

            return frameHandlerFactory.newFrameHandler(websocketPojo, upgradeRequest, upgradeResponse);
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
    public void customize(FrameHandler.CoreSession session)
    {
        factory.customize(session);
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
