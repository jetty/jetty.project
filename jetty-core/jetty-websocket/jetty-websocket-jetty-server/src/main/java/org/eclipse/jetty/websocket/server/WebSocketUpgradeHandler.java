//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.server;

import java.util.function.Consumer;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.server.WebSocketMappings;
import org.eclipse.jetty.websocket.core.server.WebSocketServerComponents;

public class WebSocketUpgradeHandler extends Handler.Wrapper
{
    public static WebSocketUpgradeHandler from(Server server, ContextHandler context)
    {
        WebSocketUpgradeHandler wsHandler = new WebSocketUpgradeHandler(WebSocketServerComponents.ensureWebSocketComponents(server, context));
        ServerWebSocketContainer container = wsHandler.container;
        context.getContext().setAttribute(WebSocketContainer.class.getName(), container);
        context.addManaged(container);
        return wsHandler;
    }

    private final ServerWebSocketContainer container;

    private WebSocketUpgradeHandler(WebSocketComponents components)
    {
        this.container = new ServerWebSocketContainer(new WebSocketMappings(components));
    }

    public WebSocketUpgradeHandler configure(Consumer<ServerWebSocketContainer> configurator)
    {
        configurator.accept(container);
        return this;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        if (container.handle(request, response, callback))
            return true;
        return super.handle(request, response, callback);
    }
}
