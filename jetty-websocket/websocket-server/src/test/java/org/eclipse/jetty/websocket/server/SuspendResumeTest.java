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
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WriteCallback;
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
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class SuspendResumeTest
{
    @WebSocket
    public static class EchoSocket
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
    
    public static class EchoCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            return new EchoSocket();
        }
    }
    
    public static class EchoServlet extends WebSocketServlet
    {
        private static final long serialVersionUID = 1L;
        
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(new EchoCreator());
        }
    }
    
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }
    
    @Test
    public void testSuspendResume() throws Exception
    {
        try (BlockheadClient client = new BlockheadClient(server.getServerUri()))
        {
            client.setTimeout(1, TimeUnit.SECONDS);
            
            client.connect();
            client.sendStandardRequest();
            client.expectUpgradeResponse();
            
            client.write(new TextFrame().setPayload("echo1"));
            client.write(new TextFrame().setPayload("echo2"));
            
            EventQueue<WebSocketFrame> frames = client.readFrames(2, 30, TimeUnit.SECONDS);
            WebSocketFrame tf = frames.poll();
            assertThat(EchoSocket.class.getSimpleName() + ".onMessage()", tf.getPayloadAsUTF8(), is("echo1"));
            tf = frames.poll();
            assertThat(EchoSocket.class.getSimpleName() + ".onMessage()", tf.getPayloadAsUTF8(), is("echo2"));
        }
    }
}
