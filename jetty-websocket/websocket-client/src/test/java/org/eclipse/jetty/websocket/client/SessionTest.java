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

package org.eclipse.jetty.websocket.client;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import java.net.URI;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.test.BlockheadConnection;
import org.eclipse.jetty.websocket.common.test.BlockheadServer;
import org.eclipse.jetty.websocket.common.test.Timeouts;
import org.junit.jupiter.api.AfterAll;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class SessionTest
{
    private static BlockheadServer server;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new BlockheadServer();
        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    @Disabled // TODO fix frequent failure
    public void testBasicEcho_FromClient() throws Exception
    {
        WebSocketClient client = new WebSocketClient();
        client.start();
        try
        {
            JettyTrackingSocket cliSock = new JettyTrackingSocket();

            // Hook into server connection creation
            CompletableFuture<BlockheadConnection> serverConnFut = new CompletableFuture<>();
            server.addConnectFuture(serverConnFut);

            client.getPolicy().setIdleTimeout(10000);

            URI wsUri = server.getWsUri();
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols("echo");
            Future<Session> future = client.connect(cliSock,wsUri,request);

            try (BlockheadConnection serverConn = serverConnFut.get(Timeouts.CONNECT, Timeouts.CONNECT_UNIT))
            {
                // Setup echo of frames on server side
                serverConn.setIncomingFrameConsumer((frame)->{
                    WebSocketFrame copy = WebSocketFrame.copy(frame);
                    serverConn.write(copy);
                });

                Session sess = future.get(30000, TimeUnit.MILLISECONDS);
                assertThat("Session", sess, notNullValue());
                assertThat("Session.open", sess.isOpen(), is(true));
                assertThat("Session.upgradeRequest", sess.getUpgradeRequest(), notNullValue());
                assertThat("Session.upgradeResponse", sess.getUpgradeResponse(), notNullValue());

                cliSock.assertWasOpened();
                cliSock.assertNotClosed();

                Collection<WebSocketSession> sessions = client.getBeans(WebSocketSession.class);
                assertThat("client.connectionManager.sessions.size", sessions.size(), is(1));

                RemoteEndpoint remote = cliSock.getSession().getRemote();
                remote.sendStringByFuture("Hello World!");
                if (remote.getBatchMode() == BatchMode.ON)
                {
                    remote.flush();
                }

                // wait for response from server
                cliSock.waitForMessage(30000, TimeUnit.MILLISECONDS);

                Set<WebSocketSession> open = client.getOpenSessions();
                assertThat("(Before Close) Open Sessions.size", open.size(), is(1));

                String received = cliSock.messageQueue.poll(Timeouts.POLL_EVENT, Timeouts.POLL_EVENT_UNIT);
                assertThat("Message", received, containsString("Hello World!"));

                cliSock.close();
            }

            cliSock.waitForClose(30000, TimeUnit.MILLISECONDS);
            Set<WebSocketSession> open = client.getOpenSessions();

            // TODO this sometimes fails!
            assertThat("(After Close) Open Sessions.size", open.size(), is(0));
        }
        finally
        {
            client.stop();
        }
    }
}
