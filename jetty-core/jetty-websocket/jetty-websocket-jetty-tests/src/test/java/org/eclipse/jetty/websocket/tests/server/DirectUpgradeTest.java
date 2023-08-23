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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.tests.EventSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class DirectUpgradeTest
{
    private Server server;
    private HttpClient httpClient;
    private WebSocketClient wsClient;

    public void start(Function<Server, ContextHandler> factory) throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);
        ContextHandler context = factory.apply(server);
        server.setHandler(context);
        server.start();

        httpClient = new HttpClient(new HttpClientTransportOverHTTP(1));
        wsClient = new WebSocketClient(httpClient);
        wsClient.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(httpClient);
        LifeCycle.stop(server);
    }

    @Test
    public void testDirectWebSocketUpgradeInChildHandler() throws Exception
    {
        start(server ->
        {
            ContextHandler context = new ContextHandler("/ctx");
            // Create a WebSocketUpgradeHandler with no mappings.
            WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
            context.setHandler(wsHandler);
            // Set up the Handler that will perform the upgrade.
            wsHandler.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception
                {
                    ServerWebSocketContainer container = ServerWebSocketContainer.get(request.getContext());
                    assertNotNull(container);
                    // Direct upgrade.
                    return container.upgrade((upgradeRequest, upgradeResponse, upgradeCallback) -> new EchoSocket(), request, response, callback);
                }
            });
            return context;
        });

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ctx/ws"));
        EventSocket clientEndpoint = new EventSocket();
        Session session = wsClient.connect(clientEndpoint, wsUri).get(5, TimeUnit.SECONDS);
        String text = "ECHO";
        session.sendText(text, null);
        String echo = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertEquals(text, echo);
    }

    @Test
    public void testDirectWebSocketUpgradeInChildHandlerWithoutWebSocketUpgradeHandler() throws Exception
    {
        start(server ->
        {
            ContextHandler context = new ContextHandler("/ctx");
            // Do not set up a WebSocketUpgradeHandler.
            // Set up the Handler that will perform the upgrade.
            context.setHandler(new Handler.Abstract()
            {
                private ServerWebSocketContainer container;

                @Override
                protected void doStart() throws Exception
                {
                    super.doStart();
                    Server server = getServer();
                    assertNotNull(server);
                    ContextHandler contextHandler = ContextHandler.getCurrentContextHandler();
                    assertNotNull(contextHandler);
                    // Alternatively, the container can be created when the ContextHandler is created.
                    container = ServerWebSocketContainer.ensure(getServer(), contextHandler);
                    assertNotNull(container);
                }

                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    // Direct upgrade.
                    return container.upgrade((upgradeRequest, upgradeResponse, upgradeCallback) -> new EchoSocket(), request, response, callback);
                }
            });
            return context;
        });

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ctx/ws"));
        EventSocket clientEndpoint = new EventSocket();
        Session session = wsClient.connect(clientEndpoint, wsUri).get(5, TimeUnit.SECONDS);
        String text = "ECHO";
        session.sendText(text, null);
        String echo = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertEquals(text, echo);
    }

    @Test
    public void testNotWebSocketUpgrade() throws Exception
    {
        start(server ->
        {
            ContextHandler context = new ContextHandler("/ctx");
            ServerWebSocketContainer container = ServerWebSocketContainer.ensure(server, context);
            // Allow for WebSocketUpgradeHandler to be subclassed.
            WebSocketUpgradeHandler wsHandler = new WebSocketUpgradeHandler(container)
            {
                @Override
                protected boolean handle(ServerWebSocketContainer container, Request request, Response response, Callback callback)
                {
                    // Modify the behavior to do a direct upgrade instead of mappings upgrade.
                    return container.upgrade((upgradeRequest, upgradeResponse, upgradeCallback) -> new EchoSocket(), request, response, callback);
                }
            };
            context.setHandler(wsHandler);

            // Since the request is not a WebSocket upgrade, this Handler will handle it.
            wsHandler.setHandler(new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback)
                {
                    Content.Sink.write(response, true, "HELLO", callback);
                    return true;
                }
            });

            return context;
        });

        // Send a request that is not a WebSocket upgrade.
        // The upgrade will not happen and the child Handler will be called.
        URI uri = server.getURI().resolve("/ctx/ws");
        ContentResponse response = httpClient.newRequest(uri)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals("HELLO", response.getContentAsString());
    }
}
