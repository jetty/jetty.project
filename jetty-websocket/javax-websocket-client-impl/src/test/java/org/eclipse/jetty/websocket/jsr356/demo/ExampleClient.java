//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.demo;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

public class ExampleClient
{
    @ClientEndpoint
    public class ExampleSocket
    {
        public String message;
        public CountDownLatch messageLatch = new CountDownLatch(1);
        public CountDownLatch closeLatch = new CountDownLatch(1);

        @OnOpen
        public void onOpen(Session session)
        {
            System.out.println("Opened");
        }

        @OnMessage
        public void onMessage(String msg)
        {
            System.out.printf("Received: %s%n", Objects.toString(msg));
            this.messageLatch.countDown();
        }

        @OnClose
        public void onClose(CloseReason close)
        {
            System.out.printf("Closed: %d, %s%n", close.getCloseCode().getCode(), Objects.toString(close.getReasonPhrase()));
            this.closeLatch.countDown();
        }
    }

    public static void main(String[] args)
    {
        try
        {
            new ExampleClient().run();
        }
        catch (Throwable t)
        {
            t.printStackTrace();
        }
    }

    private void run() throws DeploymentException, IOException, URISyntaxException, InterruptedException
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();

        System.out.printf("WebSocketContainer Impl: %s%n", container.getClass().getName());

        ExampleSocket socket = new ExampleSocket();
        URI uri = new URI("ws://echo.websocket.org/");
        Session session = container.connectToServer(socket, uri);
        RemoteEndpoint.Basic remote = session.getBasicRemote();
        String msg = "Hello world";
        System.out.printf("Sending: %s%n", Objects.toString(msg));
        remote.sendText(msg);
        socket.messageLatch.await(1, TimeUnit.SECONDS); // give remote 1 second to respond
        session.close();
        socket.closeLatch.await(1, TimeUnit.SECONDS); // give remote 1 second to acknowledge response
        System.out.println("Socket is closed");
    }
}
