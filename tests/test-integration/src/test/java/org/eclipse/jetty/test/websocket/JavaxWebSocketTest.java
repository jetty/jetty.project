//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.test.websocket;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import javax.websocket.ContainerProvider;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.test.support.XmlBasedJettyServer;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaxWebSocketTest
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
        URI uri = WSURI.toWebsocket(server.getServerURI().resolve("/test-jetty-webapp/javax.websocket"));

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        // to encourage client container to shutdown with server ...
        server.getServer().addBean(container, true);

        JavaxSimpleEchoSocket socket = new JavaxSimpleEchoSocket();
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
