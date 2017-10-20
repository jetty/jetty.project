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
import java.util.List;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseInfo;
import org.eclipse.jetty.websocket.core.WebSocketFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Sends raw TEXT or BINARY messages to server.
 * <p>
 * JSR356 Decoder resolves it to an object, and uses the JSR356 Encoder to produce an echo response.
 * </p>
 */
public class PartialEchoTest
{
    private static final Logger LOG = Log.getLogger(PartialEchoTest.class);
    
    public static class BaseSocket
    {
        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/partial/text")
    public static class PartialTextSocket extends BaseSocket
    {
        private Session session;
        private StringBuilder buf = new StringBuilder();
    
        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }
    
        @OnMessage
        public void onPartial(String msg, boolean fin) throws IOException
        {
            buf.append("('").append(msg).append("',").append(fin).append(')');
            if (fin)
            {
                session.getBasicRemote().sendText(buf.toString());
                buf.setLength(0);
            }
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/partial/text-session")
    public static class PartialTextSessionSocket extends BaseSocket
    {
        private StringBuilder buf = new StringBuilder();
        
        @OnMessage
        public void onPartial(String msg, boolean fin, Session session) throws IOException
        {
            buf.append("('").append(msg).append("',").append(fin).append(')');
            if (fin)
            {
                session.getBasicRemote().sendText(buf.toString());
                buf.setLength(0);
            }
        }
    }
    
    private static LocalServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new LocalServer()
        {
            @Override
            protected void configureServletContextHandler(ServletContextHandler context) throws Exception
            {
                ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
                container.addEndpoint(PartialTextSocket.class);
                container.addEndpoint(PartialTextSessionSocket.class);
            }
        };
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testPartialText() throws Exception
    {
        String requestPath = "/echo/partial/text";
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("Hello").setFin(false));
        send.add(new ContinuationFrame().setPayload(", ").setFin(false));
        send.add(new ContinuationFrame().setPayload("World").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("('Hello',false)(', ',false)('World',true)"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    @Test
    public void testPartialTextSession() throws Exception
    {
        String requestPath = "/echo/partial/text-session";
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("Hello").setFin(false));
        send.add(new ContinuationFrame().setPayload(", ").setFin(false));
        send.add(new ContinuationFrame().setPayload("World").setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("('Hello',false)(', ',false)('World',true)"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
