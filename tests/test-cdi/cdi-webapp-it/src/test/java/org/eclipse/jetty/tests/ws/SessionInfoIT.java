//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import org.junit.Test;

public class SessionInfoIT
{
    @ClientEndpoint
    public static class ClientSessionInfoSocket
    {
        public CountDownLatch openLatch = new CountDownLatch(1);
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public Session session;
        public EventQueue<String> messages = new EventQueue<>();
        public CloseReason closeReason;
        
        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
            this.openLatch.countDown();
        }
        
        @OnClose
        public void onClose(CloseReason close)
        {
            this.session = null;
            this.closeReason = close;
            this.closeLatch.countDown();
        }

        @OnMessage
        public void onMessage(String message)
        {
            this.messages.add(message);
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
        socket.session.getBasicRemote().sendText("close");
        
        assertThat("Await close", socket.closeLatch.await(1,TimeUnit.SECONDS),is(true));
                
        assertThat("Message", socket.messages.poll(), containsString("HttpSession = HttpSession"));
    }
}
