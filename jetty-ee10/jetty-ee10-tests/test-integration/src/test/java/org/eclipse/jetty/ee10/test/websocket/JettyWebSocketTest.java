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

package org.eclipse.jetty.ee10.test.websocket;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee10.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.ee10.websocket.api.util.WSURI;
import org.eclipse.jetty.ee10.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee10.websocket.client.WebSocketClient;
import org.eclipse.jetty.http.HttpScheme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebSocketTest
{
    private static XmlBasedJettyServer server;

    @BeforeAll
    public static void setUpServer() throws Exception
    {
        server = new XmlBasedJettyServer();
        server.setScheme(HttpScheme.HTTP.asString());
        server.addXmlConfiguration("basic-server.xml");
        server.addXmlConfiguration("login-service.xml");
        server.addXmlConfiguration("deploy.xml");
        server.addXmlConfiguration("NIOHttp.xml");

        server.load();
        // server.getServer().setDumpAfterStart(true);
        server.start();
    }

    @AfterAll
    public static void tearDownServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testChatEndpoint() throws Exception
    {
        URI uri = WSURI.toWebsocket(server.getServerURI().resolve("/demo-jetty-webapp/ws/foo"));

        WebSocketClient client = new WebSocketClient();

        try
        {
            JettySimpleEchoSocket socket = new JettySimpleEchoSocket();

            client.start();

            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols("chat");
            client.connect(socket, uri, request);
            // wait for closed socket connection.
            assertTrue(socket.awaitClose(5, TimeUnit.SECONDS));
        }
        finally
        {
            client.stop();
        }
    }
}
