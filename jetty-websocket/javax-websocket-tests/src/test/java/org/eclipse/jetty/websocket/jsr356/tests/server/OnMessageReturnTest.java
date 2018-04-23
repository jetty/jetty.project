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

package org.eclipse.jetty.websocket.jsr356.tests.server;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.websocket.CloseReason;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.jsr356.tests.WSServer;
import org.eclipse.jetty.websocket.jsr356.tests.framehandlers.FrameHandlerTracker;
import org.junit.Rule;
import org.junit.Test;

public class OnMessageReturnTest
{
    @ServerEndpoint(value = "/echoreturn")
    public static class EchoReturnEndpoint
    {
        private javax.websocket.Session session = null;
        public CloseReason close = null;
        public EventQueue<String> messageQueue = new EventQueue<>();

        public void onClose(CloseReason close)
        {
            this.close = close;
        }

        @OnMessage
        public String onMessage(String message)
        {
            this.messageQueue.offer(message);
            // Return the message
            return message;
        }

        @OnOpen
        public void onOpen(javax.websocket.Session session)
        {
            this.session = session;
        }

        public void sendText(String text) throws IOException
        {
            if (session != null)
            {
                session.getBasicRemote().sendText(text);
            }
        }
    }

    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testEchoReturn() throws Exception
    {
        WSServer wsb = new WSServer(testdir, "app");
        wsb.copyWebInf("empty-web.xml");
        wsb.copyClass(EchoReturnEndpoint.class);

        try
        {
            wsb.start();
            URI uri = wsb.getWsUri();

            WebAppContext webapp = wsb.createWebAppContext();
            wsb.deployWebapp(webapp);

            WebSocketCoreClient client = new WebSocketCoreClient();
            try
            {
                client.start();

                FrameHandlerTracker clientSocket = new FrameHandlerTracker();
                Future<FrameHandler.Channel> clientConnectFuture = client.connect(clientSocket, uri.resolve("/app/echoreturn"));

                // wait for connect
                FrameHandler.Channel channel = clientConnectFuture.get(5, TimeUnit.SECONDS);
                try
                {
                    // Send message
                    channel.sendFrame(new TextFrame().setPayload("Hello World"), Callback.NOOP, BatchMode.OFF);

                    // Confirm response
                    String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
                    assertThat("Expected message", incomingMessage, is("Hello World"));
                }
                finally
                {
                    channel.close(Callback.NOOP);
                }
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            wsb.stop();
        }
    }

}
