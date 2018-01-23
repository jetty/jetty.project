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

import java.util.concurrent.TimeUnit;

import javax.websocket.ContainerProvider;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.tests.LocalServer;
import org.eclipse.jetty.websocket.jsr356.tests.WSEndpointTracker;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class EndpointEchoTest
{
    private static LocalServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.getServerContainer().addEndpoint(LocalServer.TextEchoSocket.class);
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    public static class ClientEndpoint extends WSEndpointTracker implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            super.onOpen(session, config);
            session.addMessageHandler(this);
        }
    
        @Override
        public void onMessage(String message)
        {
            super.onWsText(message);
        }
    }
    
    @Test
    public void testEchoInstance() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        ClientEndpoint clientEndpoint = new ClientEndpoint();
        assertThat(clientEndpoint, Matchers.instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends Endpoint
        Session session = container.connectToServer(clientEndpoint, server.getServerUri());
        session.getBasicRemote().sendText("Echo");
        
        String resp = clientEndpoint.messageQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("Echo"));
        session.close();
        clientEndpoint.awaitCloseEvent("Client");
    }
    
    @Test
    public void testEchoClassRef() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        // Issue connect using class reference (class extends Endpoint)
        Session session = container.connectToServer(ClientEndpoint.class, server.getServerUri());
        session.getBasicRemote().sendText("Echo");
        
        JavaxWebSocketSession jsrSession = (JavaxWebSocketSession) session;
        Object obj = jsrSession.getEndpoint();
        
        assertThat("session.endpoint", obj, Matchers.instanceOf(ClientEndpoint.class));
        ClientEndpoint endpoint = (ClientEndpoint) obj;
        String resp = endpoint.messageQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Response echo", resp, is("Echo"));
        
        session.close();
        endpoint.awaitCloseEvent("Client");
    }
}
