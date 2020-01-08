//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.javax.tests.EventSocket;
import org.eclipse.jetty.websocket.javax.tests.WSServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static javax.websocket.CloseReason.CloseCodes.NORMAL_CLOSURE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContainerProviderServerTest
{
    @ServerEndpoint("/echo")
    public static class MySocket
    {
        @OnOpen
        public void onOpen()
        {
            WebSocketContainer client = ContainerProvider.getWebSocketContainer();
            assertNotNull(client);
        }
    }

    private WSServer server;

    @BeforeEach
    public void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(ContainerProviderServerTest.class.getName());
        server = new WSServer(testdir, "app");
        server.createWebInf();
        server.copyEndpoint(MySocket.class);
        server.start();
        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testJavaxWsContainerInServer() throws Exception
    {
        WebSocketContainer client = ContainerProvider.getWebSocketContainer();
        EventSocket clientSocket = new EventSocket();
        Session session = client.connectToServer(clientSocket, server.getWsUri().resolve("/app/echo"));
        session.close(new CloseReason(NORMAL_CLOSURE, null));
        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeReason.getCloseCode(), is(NORMAL_CLOSURE));
        assertNull(clientSocket.error);
    }
}
