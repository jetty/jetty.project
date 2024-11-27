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

package org.eclipse.jetty.ee11.websocket.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpSessionTest
{
    private Server _server;
    private ServerConnector _connector;
    private WebSocketClient _client;

    @WebSocket
    public static class HttpSessionEndpoint
    {
        private final HttpSession.Accessor accessor;

        public HttpSessionEndpoint(HttpSession.Accessor accessor)
        {
            this.accessor = accessor;
        }

        @OnWebSocketOpen
        public void onOpen(Session session)
        {
            accessor.access(httpSession -> httpSession.setAttribute("session", "setByOnOpen"));
        }

        @OnWebSocketMessage
        public void onMessage(Session session, String message)
        {
            if ("onOpenAttribute".equals(message))
            {
                try
                {
                    accessor.access(httpSession ->
                    {
                        String value = (String)httpSession.getAttribute("session");
                        session.sendText(value, Callback.NOOP);
                    });
                }
                catch (Throwable t)
                {
                    session.sendText(t.getMessage(), Callback.NOOP);
                }
            }
            else if ("invalidate".equals(message))
            {
                accessor.access(HttpSession::invalidate);
                session.sendText("success", Callback.NOOP);
            }
        }
    }

    @BeforeEach
    public void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        ServletContextHandler contextHandler = new ServletContextHandler("/", true, false);
        JettyWebSocketServletContainerInitializer.configure(contextHandler, ((servletContext, serverContainer) ->
        {
            serverContainer.addMapping("/", (req, resp) ->
            {
                HttpSession.Accessor accessor = req.getHttpServletRequest().getSession(true).getAccessor();
                return new HttpSessionEndpoint(accessor);
            });
        }));
        _server.setHandler(contextHandler);
        _server.start();

        _client = new WebSocketClient();
        _client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        _client.stop();
        _server.stop();
    }

    @Test
    public void testHttpSessionAfterWebSocketUpgrade() throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        URI uri = URI.create("ws://localhost:" + _connector.getLocalPort());
        Session session = _client.connect(clientEndpoint, uri).get();

        session.sendText("onOpenAttribute", Callback.NOOP);
        String receivedMessage = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(receivedMessage, equalTo("setByOnOpen"));

        session.sendText("invalidate", Callback.NOOP);
        receivedMessage = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(receivedMessage, equalTo("success"));

        session.sendText("onOpenAttribute", Callback.NOOP);
        receivedMessage = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
        assertThat(receivedMessage, equalTo("Invalid session"));

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientEndpoint.closeCode, equalTo(StatusCode.NORMAL));
        assertThat(clientEndpoint.closeReason, equalTo(null));
    }
}
