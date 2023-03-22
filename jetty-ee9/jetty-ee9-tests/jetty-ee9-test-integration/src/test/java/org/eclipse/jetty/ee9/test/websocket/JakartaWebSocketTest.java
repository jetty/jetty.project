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

package org.eclipse.jetty.ee9.test.websocket;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.ee9.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.ee9.websocket.api.util.WSURI;
import org.eclipse.jetty.http.HttpScheme;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JakartaWebSocketTest
{
    private static XmlBasedJettyServer server;

    @BeforeAll
    public static void setUpServer() throws Exception
    {
        server = new XmlBasedJettyServer();
        server.setScheme(HttpScheme.HTTP.asString());
        server.addXmlConfiguration("basic-server.xml");
        server.addXmlConfiguration("login-service.xml");
        // server.addXmlConfiguration("configurations-addknown-all.xml");
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
        URI uri = WSURI.toWebsocket(server.getServerURI().resolve("/ee9-demo-jetty/jakarta.websocket"));

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        // to encourage client container to shutdown with server ...
        server.getServer().addBean(container, true);

        JakartaSimpleEchoSocket socket = new JakartaSimpleEchoSocket();
        Session session = container.connectToServer(socket, uri);
        try
        {
            RemoteEndpoint.Basic remote = session.getBasicRemote();
            String msg = "Foo";
            remote.sendText(msg);
            assertTrue(socket.messageLatch.await(5, TimeUnit.SECONDS)); // give remote 1 second to respond
        }
        finally
        {
            session.close();
            assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS)); // give remote 1 second to acknowledge response
        }
    }
}
