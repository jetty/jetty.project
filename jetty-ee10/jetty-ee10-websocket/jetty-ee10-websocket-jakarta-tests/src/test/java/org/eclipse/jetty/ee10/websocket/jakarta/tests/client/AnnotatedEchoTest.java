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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.client;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.CoreServer;
import org.eclipse.jetty.io.RuntimeIOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new CoreServer(new CoreServer.EchoNegotiator());
        server.start();
    }

    @AfterAll
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
