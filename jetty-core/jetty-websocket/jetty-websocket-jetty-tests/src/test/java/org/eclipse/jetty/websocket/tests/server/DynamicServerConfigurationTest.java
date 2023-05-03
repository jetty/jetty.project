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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.tests.EventSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DynamicServerConfigurationTest
{
    private Server server;
    private HttpClient httpClient;
    private WebSocketClient wsClient;

    public void start(Handler handler) throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/ctx");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);

        wsHandler.setHandler(handler);

        server.setHandler(context);
        server.start();

        httpClient = new HttpClient();
        wsClient = new WebSocketClient(httpClient);
        wsClient.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        LifeCycle.stop(httpClient);
        LifeCycle.stop(server);
    }

    @Test
    public void testDynamicConfiguration() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, org.eclipse.jetty.util.Callback callback) throws Exception
            {
                String pathInContext = Request.getPathInContext(request);
                if ("/config".equals(pathInContext))
                {
                    ServerWebSocketContainer container = (ServerWebSocketContainer)request.getContext().getAttribute(WebSocketContainer.class.getName());
                    container.addMapping("/ws", (rq, rs, cb) -> new EchoSocket());
                }
                callback.succeeded();
                return true;
            }
        });

        // There are not yet any configured WebSocket mapping, so the connect() must fail.
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/ctx/ws"));
        Future<Session> future = wsClient.connect(new EventSocket(), wsUri);
        ExecutionException x = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(UpgradeException.class, x.getCause());

        // Make one HTTP request to dynamically configure.
        ContentResponse response = httpClient.GET(server.getURI().resolve("/ctx/config"));
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Try again WebSocket, must succeed.
        EventSocket clientEndPoint = new EventSocket();
        future = wsClient.connect(clientEndPoint, wsUri);
        try (Session session = future.get(5, SECONDS))
        {
            session.sendText("OK", Callback.NOOP);

            String reply = clientEndPoint.textMessages.poll(5, SECONDS);
            assertEquals("OK", reply);
        }
    }
}
