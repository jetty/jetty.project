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

package org.eclipse.jetty.websocket.core.server.internal;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.server.FrameHandlerFactory;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.core.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.core.server.WebSocketCreator;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;

public class CreatorNegotiator extends WebSocketNegotiator.AbstractNegotiator
{
    private final WebSocketCreator creator;
    private final FrameHandlerFactory factory;

    public CreatorNegotiator(WebSocketCreator creator, FrameHandlerFactory factory)
    {
        this(creator, factory, null);
    }

    public CreatorNegotiator(WebSocketCreator creator, FrameHandlerFactory factory, Customizer customizer)
    {
        super(customizer);
        this.creator = creator;
        this.factory = factory;
    }

    public WebSocketCreator getWebSocketCreator()
    {
        return creator;
    }

    @Override
    public FrameHandler negotiate(WebSocketNegotiation negotiation) throws IOException
    {
        ServletContext servletContext = negotiation.getRequest().getServletContext();
        if (servletContext == null)
            throw new IllegalStateException("null servletContext from request");

        ServerUpgradeRequest upgradeRequest = new ServerUpgradeRequest(negotiation);
        ServerUpgradeResponse upgradeResponse = new ServerUpgradeResponse(negotiation);

        AtomicReference<Object> result = new AtomicReference<>();
        ((ContextHandler.Context)servletContext).getContextHandler().handle(() ->
            result.set(creator.createWebSocket(upgradeRequest, upgradeResponse)));
        Object websocketPojo = result.get();

        // Handling for response forbidden (and similar paths)
        if (upgradeResponse.isCommitted())
            return null;

        if (websocketPojo == null)
        {
            // no creation, sorry
            upgradeResponse.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "WebSocket Endpoint Creation Refused");
            return null;
        }

        return factory.newFrameHandler(websocketPojo, upgradeRequest, upgradeResponse);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,%s}", getClass().getSimpleName(), hashCode(), creator, factory);
    }
}
