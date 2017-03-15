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

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.BlockheadClient;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

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
            this.acceptedProtocol = session.getUpgradeResponse().getAcceptedSubProtocol();
        }
        
        @OnWebSocketMessage
        public void onMsg(String msg)
        {
            session.getRemote().sendStringByFuture("acceptedSubprotocol=" + acceptedProtocol);
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
    
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new ProtocolServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }
    
    @Test
    public void testSingleProtocol() throws Exception
    {
        testSubProtocol("echo", "echo");
    }
    
    @Test
    public void testMultipleProtocols() throws Exception
    {
        testSubProtocol("chat,info,echo", "chat");
    }
    
    private void testSubProtocol(String requestProtocols, String acceptedSubProtocols) throws Exception
    {
        try (BlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setTimeout(1, TimeUnit.SECONDS);
            
            client.connect();
            client.addHeader("Sec-WebSocket-Protocol: "+ requestProtocols + "\r\n");
            client.sendStandardRequest();
            client.expectUpgradeResponse();
            
            client.write(new TextFrame().setPayload("showme"));
            EventQueue<WebSocketFrame> frames = client.readFrames(1, 30, TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            
            assertThat(ProtocolEchoSocket.class.getSimpleName() + ".onMessage()", tf.getPayloadAsUTF8(), is("acceptedSubprotocol=" + acceptedSubProtocols));
        }
    }
}
