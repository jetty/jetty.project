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

/**
 * <p>A {@link Handler} that may perform the upgrade from HTTP to WebSocket.</p>
 * <p>The upgrade is performed only if the request matches all the requisites
 * necessary for the upgrade (which vary upon the HTTP protocol version),
 * otherwise the request handling is forwarded to the {@link Handler} child
 * of this {@link Handler}.</p>
 * {@link WebSocketUpgradeHandler} must be a {@link #getDescendant(Class)
 * descendant} of a {@link ContextHandler}, typically as a direct child, but
 * possibly also further down the handlers tree.
 * <p>Typical usage:</p>
 * <pre>{@code
 * Server server = ...;
 *
 * ContextHandler context = new ContextHandler("/app");
 *
 * // Create the WebSocketUpgradeHandler.
 * WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
 *
 * // Link WebSocketUpgradeHandler as a child of ContextHandler.
 * context.setHandler(wsHandler);
 *
 * // Configure the WebSocketUpgradeHandler.
 * wsHandler.configure(container ->
 * {
 *     // Map upgrade requests to "/app/ws" to an echo WebSocket endpoint.
 *     container.addMapping("/ws", (upgradeRequest, upgradeResponse, callback) -> new EchoEndPoint());
 * });
 *
 * server.setHandler(context);
 * server.start();
 * }</pre>
 * <p>A {@link WebSocketUpgradeHandler} is associated with a {@link ServerWebSocketContainer}
 * that is exported as a request context attribute and can be retrieved in this way:
 * <pre>{@code
 * public boolean process(Request request)
 * {
 *     // Retrieve the WebSocket container from the context attributes.
 *     ServerWebSocketContainer container = (ServerWebSocketContainer)request.getContext().getAttribute(WebSocketContainer.class.getName());
 * }
 * }</pre>
 */
public class WebSocketUpgradeHandler extends Handler.Wrapper
{
    /**
     * <p>Creates a new {@link WebSocketUpgradeHandler}.</p>
     * <p>The {@link WebSocketUpgradeHandler} is not yet linked to the given
     * {@link ContextHandler}, therefore the caller code must ensure that
     * the returned {@link WebSocketUpgradeHandler} is a descendant of the
     * given {@link ContextHandler}.</p>
     *
     * @param server the {@link Server} object used to lookup common WebSocket components
     * @param context the {@link ContextHandler} ancestor of the returned {@link WebSocketUpgradeHandler}
     * @return a new {@link WebSocketUpgradeHandler}
     * @see #configure(Consumer)
     */
    public static WebSocketUpgradeHandler from(Server server, ContextHandler context)
    {
        WebSocketUpgradeHandler wsHandler = new WebSocketUpgradeHandler(WebSocketServerComponents.ensureWebSocketComponents(server, context));
        context.getContext().setAttribute(WebSocketContainer.class.getName(), wsHandler.container);
        return wsHandler;
    }

    private final ServerWebSocketContainer container;

    private WebSocketUpgradeHandler(WebSocketComponents components)
    {
        this.container = new ServerWebSocketContainer(new WebSocketMappings(components));
        addBean(container);
    }

    /**
     * <p>Configures the {@link ServerWebSocketContainer} associated with this
     * {@link WebSocketUpgradeHandler}.</p>
     *
     * @param configurator the configuration code
     * @return this {@link WebSocketUpgradeHandler}
     */
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

    @Override
    public InvocationType getInvocationType()
    {
        // Must be BLOCKING because EndPoint.upgrade() ends up invoking
        // application code in the WebSocket "connect" event handler,
        // e.g. a method annotated with @OnWebSocketConnect, that may block.
        return InvocationType.NON_BLOCKING;
    }
}
