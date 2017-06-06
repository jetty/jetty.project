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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SessionTrackingTest
{
    @WebSocket
    public static class SessionTrackingSocket
    {
        private final WebSocketServerFactory container;
        
        public SessionTrackingSocket(WebSocketServerFactory container)
        {
            this.container = container;
        }
        
        @OnWebSocketMessage
        public void onMessage(Session session, String msg) throws IOException
        {
            if (msg == null)
            {
                session.getRemote().sendString("Unknown command: <null>");
                return;
            }
            
            String parts[] = msg.split("\\|");
            
            if ("openSessions".equals(parts[0]))
            {
                Collection<WebSocketSession> sessions = container.getOpenSessions();
                String ret = String.format("openSessions(@%s).size=%d", parts[1], sessions.size());
                session.getRemote().sendString(ret);
                return;
            }
            
            session.getRemote().sendString("Unknown command: " + msg);
        }
    }
    
    public static class SessionTrackingServlet extends WebSocketServlet implements WebSocketCreator
    {
        private WebSocketServerFactory serverFactory;
        
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            // If this fails, then we have a lot of tests failing.
            this.serverFactory = (WebSocketServerFactory) factory;
            factory.setCreator(this);
        }
        
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            return new SessionTrackingSocket(serverFactory);
        }
    }
    
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new SessionTrackingServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testAddRemoveSessions() throws Exception
    {
        try (LocalFuzzer session1 = server.newLocalFuzzer("/1"))
        {
            sendTextFrameToAll("openSessions|in-1", session1);
            
            try (LocalFuzzer session2 = server.newLocalFuzzer("/2"))
            {
                sendTextFrameToAll("openSessions|in-2", session1, session2);
                
                try (LocalFuzzer session3 = server.newLocalFuzzer("/3"))
                {
                    sendTextFrameToAll("openSessions|in-3", session1, session2, session3);
                    sendTextFrameToAll("openSessions|lvl-3", session1, session2, session3);
                    
                    session3.sendFrames(new CloseFrame());
                    
                    List<WebSocketFrame> expect3 = new ArrayList<>();
                    expect3.add(new TextFrame().setPayload("openSessions(@in-3).size=3"));
                    expect3.add(new TextFrame().setPayload("openSessions(@lvl-3).size=3"));
                    expect3.add(new CloseFrame());
                    session3.expect(expect3);
                }
    
                sendTextFrameToAll("openSessions|lvl-2", session1, session2);
                session2.sendFrames(new CloseFrame());
                
                List<WebSocketFrame> expect2 = new ArrayList<>();
                expect2.add(new TextFrame().setPayload("openSessions(@in-2).size=2"));
                expect2.add(new TextFrame().setPayload("openSessions(@in-3).size=3"));
                expect2.add(new TextFrame().setPayload("openSessions(@lvl-3).size=3"));
                expect2.add(new TextFrame().setPayload("openSessions(@lvl-2).size=2"));
                expect2.add(new CloseFrame());
                session2.expect(expect2);
            }
    
            sendTextFrameToAll("openSessions|lvl-1", session1);
            session1.sendFrames(new CloseFrame());
            
            List<WebSocketFrame> expect1 = new ArrayList<>();
            expect1.add(new TextFrame().setPayload("openSessions(@in-1).size=1"));
            expect1.add(new TextFrame().setPayload("openSessions(@in-2).size=2"));
            expect1.add(new TextFrame().setPayload("openSessions(@in-3).size=3"));
            expect1.add(new TextFrame().setPayload("openSessions(@lvl-3).size=3"));
            expect1.add(new TextFrame().setPayload("openSessions(@lvl-2).size=2"));
            expect1.add(new TextFrame().setPayload("openSessions(@lvl-1).size=1"));
            expect1.add(new CloseFrame());
            session1.expect(expect1);
        }
    }
    
    private void sendTextFrameToAll(String msg, LocalFuzzer... sessions)
    {
        for (LocalFuzzer session : sessions)
        {
            session.sendFrames(new TextFrame().setPayload(msg));
        }
    }
}
