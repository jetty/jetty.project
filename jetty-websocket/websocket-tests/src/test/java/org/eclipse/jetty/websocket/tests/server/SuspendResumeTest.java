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

package org.eclipse.jetty.websocket.tests.server;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

public class SuspendResumeTest
{
    @WebSocket
    public static class BackPressureEchoSocket
    {
        private Session session;
        
        @OnWebSocketConnect
        public void onConnect(Session session)
        {
            this.session = session;
        }
        
        @OnWebSocketMessage
        public void onMessage(String message)
        {
            SuspendToken suspendToken = this.session.suspend();
            this.session.getRemote().sendString(message,
                    new WriteCallback()
                    {
                        @Override
                        public void writeSuccess()
                        {
                            suspendToken.resume();
                        }
                        
                        @Override
                        public void writeFailed(Throwable t)
                        {
                            Assert.fail(t.getMessage());
                        }
                    });
        }
    }
    
    public static class BackPressureEchoCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            return new BackPressureEchoSocket();
        }
    }
    
    public static class BackPressureServlet extends WebSocketServlet
    {
        private static final long serialVersionUID = 1L;
        
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(new BackPressureEchoCreator());
        }
    }
    
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new BackPressureServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    @Ignore("Not working yet")
    public void testSuspendResume() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("echo1"));
        send.add(new TextFrame().setPayload("echo2"));
        send.add(new CloseFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("echo1"));
        expect.add(new TextFrame().setPayload("echo2"));
        expect.add(new CloseFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    @Test
    @Ignore("Not working yet")
    public void testSuspendResume_SmallBuffers() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("echo1"));
        send.add(new TextFrame().setPayload("echo2"));
        send.add(new CloseFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("echo1"));
        expect.add(new TextFrame().setPayload("echo2"));
        expect.add(new CloseFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendSegmented(send, 2);
            session.expect(expect);
        }
    }
}
