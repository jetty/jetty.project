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

package org.eclipse.jetty.ee9.websocket.tests;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.HttpResponse;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.api.ExtensionConfig;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.client.JettyUpgradeListener;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebSocketExtensionConfigTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configure(contextHandler,
            (context, container) -> container.addMapping("/", (req, resp) ->
            {
                assertEquals(req.getExtensions().stream().filter(e -> e.getName().equals("permessage-deflate")).count(), 1);
                assertEquals(resp.getExtensions().stream().filter(e -> e.getName().equals("permessage-deflate")).count(), 1);

                ExtensionConfig nonRequestedExtension = ExtensionConfig.parse("identity");
                assertNotNull(nonRequestedExtension);

                assertThrows(IllegalArgumentException.class,
                    () -> resp.setExtensions(List.of(nonRequestedExtension)),
                    "should not allow extensions not requested");

                // Check identity extension was not added because it was not requested
                assertEquals(resp.getExtensions().stream().filter(config -> config.getName().equals("identity")).count(), 0);
                assertEquals(resp.getExtensions().stream().filter(e -> e.getName().equals("permessage-deflate")).count(), 1);

                return new EchoSocket();
            }));

        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
    }

    @Test
    public void testJettyExtensionConfig() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/filterPath");
        EventSocket socket = new EventSocket();

        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.addExtensions(ExtensionConfig.parse("permessage-deflate"));

        CountDownLatch correctResponseExtensions = new CountDownLatch(1);
        JettyUpgradeListener listener = new JettyUpgradeListener()
        {
            @Override
            public void onHandshakeResponse(HttpRequest request, HttpResponse response)
            {

                String extensions = response.getHeaders().get(HttpHeader.SEC_WEBSOCKET_EXTENSIONS);
                if ("permessage-deflate".equals(extensions))
                    correctResponseExtensions.countDown();
                else
                    throw new IllegalStateException("Unexpected Negotiated Extensions: " + extensions);
            }
        };

        CompletableFuture<Session> connect = client.connect(socket, uri, request, listener);
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            session.getRemote().sendString("hello world");
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(correctResponseExtensions.await(5, TimeUnit.SECONDS));

        String msg = socket.textMessages.poll();
        assertThat(msg, is("hello world"));
    }
}
