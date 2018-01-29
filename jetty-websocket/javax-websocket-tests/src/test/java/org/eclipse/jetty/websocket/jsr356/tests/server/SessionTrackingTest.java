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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.jsr356.tests.Fuzzer;
import org.eclipse.jetty.websocket.jsr356.tests.LocalServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class SessionTrackingTest
{
    @ServerEndpoint("/session-info")
    public static class SessionTrackingSocket
    {
        @OnMessage
        public void onMessage(Session session, String msg) throws IOException
        {
            if (msg == null)
            {
                session.getBasicRemote().sendText("Unknown command: <null>");
                return;
            }
            
            String parts[] = msg.split("\\|");
            
            if ("openSessions".equals(parts[0]))
            {
                Collection<Session> sessions = session.getOpenSessions();
                String ret = String.format("openSessions(@%s).size=%d", parts[1], sessions.size());
                session.getBasicRemote().sendText(ret);
                return;
            }
            
            session.getBasicRemote().sendText("Unknown command: " + msg);
        }
    }
    
    private static LocalServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.getServerContainer().addEndpoint(SessionTrackingSocket.class);
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
        try (Fuzzer session1 = server.newNetworkFuzzer("/1"))
        {
            sendTextFrameToAll("openSessions|in-1", session1);
            
            try (Fuzzer session2 = server.newNetworkFuzzer("/2"))
            {
                sendTextFrameToAll("openSessions|in-2", session1, session2);
                
                try (Fuzzer session3 = server.newNetworkFuzzer("/3"))
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
    
    private void sendTextFrameToAll(String msg, Fuzzer... sessions) throws IOException
    {
        for (Fuzzer session : sessions)
        {
            session.sendFrames(new TextFrame().setPayload(msg));
        }
    }
}
