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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class JettyWebsocketTest
{

    @WebSocket
    public static class EventSocket
    {
        CountDownLatch closed = new CountDownLatch(1);

        String behavior;

        @OnWebSocketConnect
        public void onOpen(Session sess)
        {
            behavior = sess.getPolicy().getBehavior().name();
            System.err.println(toString() + " Socket Connected: " + sess);
        }

        @OnWebSocketMessage
        public void onMessage(String message)
        {
            System.err.println(toString() + " Received TEXT message: " + message);
        }

        @OnWebSocketClose
        public void onClose(int statusCode, String reason)
        {
            System.err.println(toString() + " Socket Closed: " + statusCode + ":" + reason);
            closed.countDown();
        }

        @OnWebSocketError
        public void onError(Throwable cause)
        {
            cause.printStackTrace(System.err);
        }

        @Override
        public String toString()
        {
            return String.format("[%s@%s]", behavior, Integer.toHexString(hashCode()));
        }
    }

    public static class MyWebSocketServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.addMapping("/",(req, resp)->new EventSocket());
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

        contextHandler.addServlet(MyWebSocketServlet.class, "/testPath1");
        contextHandler.addServlet(MyWebSocketServlet.class, "/testPath2");

        try
        {
            JettyWebSocketServletContainerInitializer.configure(contextHandler);
            server.start();

            WebSocketClient client = new WebSocketClient();
            client.start();

            URI uri = URI.create("ws://localhost:8080/testPath1");
            EventSocket socket = new EventSocket();
            CompletableFuture<Session> connect = client.connect(socket, uri);
            try(Session session = connect.get(5, TimeUnit.SECONDS))
            {
                session.getRemote().sendString("hello world");
            }
            assertTrue(socket.closed.await(10, TimeUnit.SECONDS));


            uri = URI.create("ws://localhost:8080/testPath2");
            socket = new EventSocket();
            connect = client.connect(socket, uri);
            try(Session session = connect.get(5, TimeUnit.SECONDS))
            {
                session.getRemote().sendString("hello world");
            }
            assertTrue(socket.closed.await(10, TimeUnit.SECONDS));


            server.stop();
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }
}
