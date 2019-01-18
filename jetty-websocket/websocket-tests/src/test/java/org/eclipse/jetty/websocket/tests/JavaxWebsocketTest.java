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

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.javax.server.JavaxWebSocketServletContainerInitializer;
import org.junit.jupiter.api.Test;

public class JavaxWebsocketTest
{

    @ClientEndpoint
    @ServerEndpoint("/path")
    public static class EventSocket
    {
        CountDownLatch closed = new CountDownLatch(1);

        @OnOpen
        public void onOpen(Session sess)
        {
            System.out.println("Socket Connected: " + sess);
        }

        @OnMessage
        public void onMessage(String message)
        {
            System.out.println("Received TEXT message: " + message);
        }

        @OnClose
        public void onClose(CloseReason reason)
        {
            System.out.println("Socket Closed: " + reason);
            closed.countDown();
        }

        @OnError
        public void onError(Throwable cause)
        {
            cause.printStackTrace(System.err);
        }
    }


    @Test
    public void test() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(8080);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        try
        {
            ServerContainer serverContainer = JavaxWebSocketServletContainerInitializer.configureContext(contextHandler);
            serverContainer.addEndpoint(EventSocket.class);
            server.start();

            URI uri = URI.create("ws://localhost:8080/path");
            WebSocketContainer clientContainer = ContainerProvider.getWebSocketContainer();

            EventSocket clientEndpoint = new EventSocket();
            try(Session session = clientContainer.connectToServer(clientEndpoint, uri))
            {
                session.getBasicRemote().sendText("hello world");
            }

            clientEndpoint.closed.await(10, TimeUnit.SECONDS);
            server.stop();
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }
}
