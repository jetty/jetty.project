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

import org.eclipse.jetty.server.Context;
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
        Context context = negotiation.getRequest().getContext();
        ServerUpgradeRequest upgradeRequest = new ServerUpgradeRequest(negotiation);
        ServerUpgradeResponse upgradeResponse = new ServerUpgradeResponse(negotiation);

        Object websocketPojo;
        try
        {
            AtomicReference<Object> result = new AtomicReference<>();
            context.run(() -> result.set(creator.createWebSocket(upgradeRequest, upgradeResponse, negotiation.getCallback())));
            websocketPojo = result.get();
        }
        catch (Throwable t)
        {
            negotiation.getCallback().failed(t);
            return null;
        }

        if (websocketPojo == null)
            return null;
        return factory.newFrameHandler(websocketPojo, upgradeRequest, upgradeResponse);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s,%s}", getClass().getSimpleName(), hashCode(), creator, factory);
    }
}
