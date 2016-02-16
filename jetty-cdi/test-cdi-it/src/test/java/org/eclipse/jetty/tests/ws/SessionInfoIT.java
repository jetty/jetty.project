//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.tests.ws;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.Test;

public class SessionInfoIT
{
    @ClientEndpoint
    public static class ClientSessionInfoSocket
    {
        private static final Logger LOG = Log.getLogger(SessionInfoIT.ClientSessionInfoSocket.class);
        
        public CountDownLatch openLatch = new CountDownLatch(1);
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public Session session;
        public EventQueue<String> messages = new EventQueue<>();
        public CloseReason closeReason;
        
        @OnOpen
        public void onOpen(Session session)
        {
            LOG.info("onOpen(): {}", session);
            this.session = session;
            this.openLatch.countDown();
        }
        
        @OnClose
        public void onClose(CloseReason close)
        {
            LOG.info("onClose(): {}", close);
            this.session = null;
            this.closeReason = close;
            this.closeLatch.countDown();
        }

        @OnMessage
        public void onMessage(String message)
        {
            LOG.info("onMessage(): {}", message);
            this.messages.offer(message);
        }
    }

    @Test
    public void testSessionInfo() throws Exception
    {
        URI serverURI = new URI("ws://localhost:58080/cdi-webapp/");

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        ClientSessionInfoSocket socket = new ClientSessionInfoSocket();
        
        container.connectToServer(socket,serverURI.resolve("sessioninfo"));

        assertThat("Await open", socket.openLatch.await(1,TimeUnit.SECONDS), is(true));
        
        socket.session.getBasicRemote().sendText("info");
        socket.messages.awaitEventCount(1,2,TimeUnit.SECONDS);
        
        System.out.printf("socket.messages.size = %s%n",socket.messages.size());
        
        String msg = socket.messages.poll();
        System.out.printf("Message is [%s]%n",msg);
        
        assertThat("Message", msg, containsString("HttpSession = HttpSession"));
        
        socket.session.getBasicRemote().sendText("close");
        assertThat("Await close", socket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
    }
}
