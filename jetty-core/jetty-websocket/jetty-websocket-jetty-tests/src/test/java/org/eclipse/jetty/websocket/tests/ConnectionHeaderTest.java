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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.JettyUpgradeListener;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ConnectionHeaderTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;

    @BeforeEach
    public void startContainers() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler("/");

        WebSocketUpgradeHandler wsHandler = WebSocketUpgradeHandler.from(server, context);
        context.setHandler(wsHandler);
        wsHandler.configure(container ->
            container.addMapping("/echo", (rq, rs, cb) -> new EchoSocket()));

        server.setHandler(context);
        server.start();

        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
    public void stopContainers() throws Exception
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
                if (!(fields instanceof HttpFields.Mutable headers))
                    throw new IllegalStateException(fields.getClass().getName());

                // Replace the default connection header value with a custom one.
                headers.put(HttpHeader.CONNECTION, connectionHeaderValue);
            }
        };

        EventSocket clientEndpoint = new EventSocket();
        try (Session session = client.connect(clientEndpoint, uri, null, upgradeListener).get(5, TimeUnit.SECONDS))
        {
            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            session.sendText(msg, Callback.NOOP);

            // Read frame (hopefully text frame)
            String response = clientEndpoint.textMessages.poll(5, TimeUnit.SECONDS);
            assertThat("Text Frame.status code", response, is(msg));
        }
    }
}
