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

package org.eclipse.jetty.websocket.tests.server;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class SubProtocolTest
{
    @WebSocket
    public static class ProtocolEchoSocket
    {
        private Session session;
        private String acceptedProtocol;
        
        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            this.session = session;
            this.acceptedProtocol = session.getHandshakeResponse().getAcceptedSubProtocol();
        }
        
        @OnWebSocketMessage
        public void onMsg(String msg)
        {
            session.getRemote().sendText("acceptedSubprotocol=" + acceptedProtocol, Callback.NOOP);
        }
    }
    
    public static class ProtocolCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            // Accept first sub-protocol
            if (req.getSubProtocols() != null)
            {
                if (!req.getSubProtocols().isEmpty())
                {
                    String subProtocol = req.getSubProtocols().get(0);
                    resp.setAcceptedSubProtocol(subProtocol);
                }
            }
            
            return new ProtocolEchoSocket();
        }
    }
    
    public static class ProtocolServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(new ProtocolCreator());
        }
    }

    private TestInfo testInfo;
    private static SimpleServletServer server;
    
    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new ProtocolServlet());
        server.start();
    }
    
    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    private WebSocketClient client;
    
    @BeforeEach
    public void startClient(TestInfo testInfo) throws Exception
    {
        this.testInfo = testInfo;
        client = new WebSocketClient();
        client.start();
    }
    
    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    @Test
    public void testSingleProtocol() throws Exception
    {
        testSubProtocol(new String[]{"echo"}, "echo");
    }
    
    @Test
    public void testMultipleProtocols() throws Exception
    {
        testSubProtocol(new String[]{"chat", "info", "echo"}, "chat");
    }
    
    private void testSubProtocol(String[] requestProtocols, String acceptedSubProtocols) throws Exception
    {
        URI wsUri = server.getWsUri();
        client.setMaxIdleTimeout(1000);
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testInfo.getTestMethod().toString());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols(requestProtocols);
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // Message
        clientSession.getRemote().sendString("showme");
        
        // Read message
        String incomingMsg = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Incoming Message", incomingMsg, is("acceptedSubprotocol=" + acceptedSubProtocols));
        
        clientSession.close();
    }
}
