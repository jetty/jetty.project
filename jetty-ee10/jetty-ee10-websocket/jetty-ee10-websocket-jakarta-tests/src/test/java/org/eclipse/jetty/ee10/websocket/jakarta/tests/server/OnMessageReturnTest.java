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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.CloseReason;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.WSServer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.framehandlers.FrameHandlerTracker;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class OnMessageReturnTest
{
    @ServerEndpoint(value = "/echoreturn")
    public static class EchoReturnEndpoint
    {
        private jakarta.websocket.Session session = null;
        public CloseReason close = null;
        public LinkedBlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();

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
        public void onOpen(jakarta.websocket.Session session)
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

    public WorkDir testdir;

    @Test
    public void testEchoReturn() throws Exception
    {
        WSServer wsb = new WSServer(testdir.getPath());
        WSServer.WebApp app = wsb.createWebApp("app");
        app.copyWebInf("empty-web.xml");
        app.copyClass(EchoReturnEndpoint.class);
        app.deploy();

        try
        {
            wsb.start();
            URI uri = wsb.getWsUri();

            WebSocketCoreClient client = new WebSocketCoreClient();
            try
            {
                client.start();

                FrameHandlerTracker clientSocket = new FrameHandlerTracker();
                Future<CoreSession> clientConnectFuture = client.connect(clientSocket, uri.resolve("/app/echoreturn"));

                // wait for connect
                CoreSession coreSession = clientConnectFuture.get(5, TimeUnit.SECONDS);
                try
                {
                    // Send message
                    coreSession.sendFrame(new Frame(OpCode.TEXT).setPayload("Hello World"), Callback.NOOP, false);

                    // Confirm response
                    String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
                    assertThat("Expected message", incomingMessage, is("Hello World"));
                }
                finally
                {
                    coreSession.close(Callback.NOOP);
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
