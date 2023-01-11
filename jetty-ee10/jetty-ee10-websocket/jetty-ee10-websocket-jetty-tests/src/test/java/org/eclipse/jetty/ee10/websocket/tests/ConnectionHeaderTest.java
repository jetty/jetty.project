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

package org.eclipse.jetty.ee10.websocket.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.api.Session;
import org.eclipse.jetty.ee10.websocket.client.JettyUpgradeListener;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ConnectionHeaderTest
{
    private static WebSocketClient client;
    private static Server server;
    private static ServerConnector connector;

    @BeforeAll
    public static void startContainers() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (servletContext, container) ->
            container.addMapping("/echo", EchoSocket.class));

        server.setHandler(contextHandler);
        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterAll
    public static void stopContainers() throws Exception
    {
        client.stop();
        server.stop();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Upgrade", "keep-alive, Upgrade", "close, Upgrade"})
    public void testConnectionKeepAlive(String connectionHeaderValue) throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/echo");
        JettyUpgradeListener upgradeListener = new JettyUpgradeListener()
        {
            @Override
            public void onHandshakeRequest(Request request)
            {
                HttpFields fields = request.getHeaders();
                if (!(fields instanceof HttpFields.Mutable))
                    throw new IllegalStateException(fields.getClass().getName());

                // Replace the default connection header value with a custom one.
                HttpFields.Mutable headers = (HttpFields.Mutable)fields;
                headers.put(HttpHeader.CONNECTION, connectionHeaderValue);
            }
        };

        EventSocket clientEndpoint = new EventSocket();
        try (Session session = client.connect(clientEndpoint, uri, null, upgradeListener).get(5, TimeUnit.SECONDS))
        {
            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            session.getRemote().sendString(msg);

            // Read frame (hopefully text frame)
            String response = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat("Text Frame.status code", response, is(msg));
        }
    }
}
