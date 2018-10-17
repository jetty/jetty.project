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

import java.util.Arrays;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.jsr356.tests.Fuzzer;
import org.eclipse.jetty.websocket.jsr356.tests.LocalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SessionTrackingTest
{
    @ServerEndpoint("/session-info/{sessionId}")
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
    
    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(SessionTrackingSocket.class);
    }
    
    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testAddRemoveSessions() throws Exception
    {
        List<Frame> expectedFrames = new ArrayList<>();

        try (Fuzzer session1 = server.newNetworkFuzzer("/session-info/1"))
        {
            expectedFrames.clear();
            sendTextFrameToAll("openSessions|in-1", session1);
            session1.expect(Arrays.asList(new Frame(OpCode.TEXT).setPayload("openSessions(@in-1).size=1")));

            try (Fuzzer session2 = server.newNetworkFuzzer("/session-info/2"))
            {
                expectedFrames.clear();
                sendTextFrameToAll("openSessions|in-2", session1, session2);
                session2.expect(Arrays.asList(new Frame(OpCode.TEXT).setPayload("openSessions(@in-2).size=2")));
                session1.expect(Arrays.asList(new Frame(OpCode.TEXT).setPayload("openSessions(@in-2).size=2")));

                try (Fuzzer session3 = server.newNetworkFuzzer("/session-info/3"))
                {
                    sendTextFrameToAll("openSessions|in-3", session1, session2, session3);
                    sendTextFrameToAll("openSessions|lvl-3", session1, session2, session3);
                    session3.sendFrames(new Frame(OpCode.CLOSE));

                    expectedFrames.clear();
                    expectedFrames.add(new Frame(OpCode.TEXT).setPayload("openSessions(@in-3).size=3"));
                    expectedFrames.add(new Frame(OpCode.TEXT).setPayload("openSessions(@lvl-3).size=3"));
                    expectedFrames.add(new Frame(OpCode.CLOSE));
                    session3.expect(expectedFrames);


                    expectedFrames.clear();
                    expectedFrames.add(new Frame(OpCode.TEXT).setPayload("openSessions(@in-3).size=3"));
                    expectedFrames.add(new Frame(OpCode.TEXT).setPayload("openSessions(@lvl-3).size=3"));
                    session1.expect(expectedFrames);
                    session2.expect(expectedFrames);
                }
    
                sendTextFrameToAll("openSessions|lvl-2", session1, session2);
                session2.sendFrames(new Frame(OpCode.CLOSE));
                
                expectedFrames.clear();
                expectedFrames.add(new Frame(OpCode.TEXT).setPayload("openSessions(@lvl-2).size=2"));
                expectedFrames.add(new Frame(OpCode.CLOSE));
                session2.expect(expectedFrames);

                session1.expect(Arrays.asList(new Frame(OpCode.TEXT).setPayload("openSessions(@lvl-2).size=2")));
            }
    
            sendTextFrameToAll("openSessions|lvl-1", session1);
            session1.sendFrames(new Frame(OpCode.CLOSE));
            
            expectedFrames.clear();
            expectedFrames.add(new Frame(OpCode.TEXT).setPayload("openSessions(@lvl-1).size=1"));
            expectedFrames.add(new Frame(OpCode.CLOSE));
            session1.expect(expectedFrames);
        }
    }
    
    private void sendTextFrameToAll(String msg, Fuzzer... sessions) throws IOException
    {
        for (Fuzzer session : sessions)
            session.sendFrames(new Frame(OpCode.TEXT).setPayload(msg));
    }
}
