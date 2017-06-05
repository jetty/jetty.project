//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server.jsr356;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.tests.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.WSServer;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test Echo of Large messages, targeting the {@link javax.websocket.Session#setMaxTextMessageBufferSize(int)} functionality
 */
@Ignore
public class LargeAnnotatedTest
{
    @ServerEndpoint(value = "/echo/large")
    public static class LargeEchoConfiguredSocket
    {
        private javax.websocket.Session session;
        
        @OnOpen
        public void open(javax.websocket.Session session)
        {
            this.session = session;
            this.session.setMaxTextMessageBufferSize(128 * 1024);
        }
        
        @OnMessage
        public void echo(String msg)
        {
            // reply with echo
            session.getAsyncRemote().sendText(msg);
        }
    }
    
    @Rule
    public TestingDir testdir = new TestingDir();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    @SuppressWarnings("Duplicates")
    @Test
    @Ignore("Not working yet")
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir,"app");
        wsb.createWebInf();
        wsb.copyEndpoint(LargeEchoConfiguredSocket.class);

        try
        {
            wsb.start();
            URI uri = wsb.getServerUri();

            WebAppContext webapp = wsb.createWebAppContext();
            wsb.deployWebapp(webapp);
            // wsb.dump();

            WebSocketClient client = new WebSocketClient(bufferPool);
            try
            {
                client.getPolicy().setMaxTextMessageSize(128*1024);
                client.start();
    
                TrackingEndpoint clientSocket = new TrackingEndpoint("Client");
                
                Future<Session> clientConnectFuture = client.connect(clientSocket,uri.resolve("/app/echo/large"));
                // wait for connect
                Session clientSession = clientConnectFuture.get(1,TimeUnit.SECONDS);
                
                // The message size should be bigger than default, but smaller than the limit that LargeEchoSocket specifies
                byte txt[] = new byte[100 * 1024];
                Arrays.fill(txt,(byte)'o');
                String msg = new String(txt,StandardCharsets.UTF_8);
                clientSession.getRemote().sendString(msg);
                
                // Receive echo
                String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
                assertThat("Expected message",incomingMessage,is(msg));
                
                clientSession.close();
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
