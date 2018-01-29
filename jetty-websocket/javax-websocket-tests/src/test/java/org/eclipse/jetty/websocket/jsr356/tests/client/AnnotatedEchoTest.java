//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests.client;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.ContainerProvider;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.websocket.jsr356.tests.CoreServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class AnnotatedEchoTest
{
    public static class EchoSocketBase
    {
        protected Session session = null;
        public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();

        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }
    }

    @ClientEndpoint
    public static class EchoViaAsync extends EchoSocketBase
    {
        @OnMessage
        public void onMessage(String message)
        {
            this.messageQueue.offer(message);
            if (message.startsWith("echo|"))
            {
                String resp = message.substring("echo|".length());
                session.getAsyncRemote().sendText(resp);
            }
        }
    }

    @ClientEndpoint
    public static class EchoViaBasic extends EchoSocketBase
    {
        @OnMessage
        public void onMessage(String message)
        {
            this.messageQueue.offer(message);
            if (message.startsWith("echo|"))
            {
                String resp = message.substring("echo|".length());
                try
                {
                    session.getBasicRemote().sendText(resp);
                }
                catch (IOException e)
                {
                    throw new RuntimeIOException(e);
                }
            }
        }
    }

    @ClientEndpoint
    public static class EchoViaReturn extends EchoSocketBase
    {
        @OnMessage
        public String onMessage(String message)
        {
            this.messageQueue.offer(message);
            if (message.startsWith("echo|"))
            {
                String resp = message.substring("echo|".length());
                return resp;
            }
            return null;
        }
    }

    private static CoreServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new CoreServer(new CoreServer.EchoNegotiator());
        server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private void assertEchoWorks(Session session, EchoSocketBase socketBase, String text) throws IOException, InterruptedException
    {
        session.getBasicRemote().sendText("echo|" + text);
        String msg = socketBase.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("message 1", msg, is("echo|" + text));
        msg = socketBase.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("message 2", msg, is(text));
    }

    @Test
    public void testEchoViaAsync() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        Session session = null;
        try
        {
            EchoViaAsync clientSocket = new EchoViaAsync();
            session = container.connectToServer(clientSocket, server.getWsUri().resolve("/echo/text"));
            assertEchoWorks(session, clientSocket, "hello");
        }
        finally
        {
            session.close();
        }
    }

    @Test
    public void testEchoViaBasic() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        Session session = null;
        try
        {
            EchoViaBasic clientSocket = new EchoViaBasic();
            session = container.connectToServer(clientSocket, server.getWsUri().resolve("/echo/text"));
            assertEchoWorks(session, clientSocket, "world");
        }
        finally
        {
            session.close();
        }
    }

    @Test
    public void testEchoViaReturn() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server
        Session session = null;
        try
        {
            EchoViaReturn clientSocket = new EchoViaReturn();
            session = container.connectToServer(clientSocket, server.getWsUri().resolve("/echo/text"));
            assertEchoWorks(session, clientSocket, "its me");
        }
        finally
        {
            session.close();
        }
    }
}
