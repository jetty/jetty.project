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

public class DirectUpgradeTest
{
    private Server server;
    private HttpClient httpClient;
    private WebSocketClient wsClient;

    public void start(Function<ServerWebSocketContainer, WebSocketUpgradeHandler> factory, Handler handler) throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server, 1, 1);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/ctx");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context, factory);
        context.setHandler(wsHandler);

        wsHandler.setHandler(handler);

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
    public void testDirectWebSocketUpgrade() throws Exception
    {
        start(container -> new WebSocketUpgradeHandler(container)
        {
            @Override
            protected boolean handle(ServerWebSocketContainer container, Request request, Response response, Callback callback)
            {
                return container.upgrade((upgradeRequest, upgradeResponse, upgradeCallback) -> new EchoSocket(), request, response, callback);
            }
        }, null);

        // No mappings added, direct upgrade in handle() above, connect() must succeed.
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
        start(container -> new WebSocketUpgradeHandler(container)
        {
            @Override
            protected boolean handle(ServerWebSocketContainer container, Request request, Response response, Callback callback)
            {
                return container.upgrade((upgradeRequest, upgradeResponse, upgradeCallback) -> new EchoSocket(), request, response, callback);
            }
        }, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, "HELLO", callback);
                return true;
            }
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
