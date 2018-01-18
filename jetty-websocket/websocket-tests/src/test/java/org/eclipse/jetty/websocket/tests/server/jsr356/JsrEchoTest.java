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

package org.eclipse.jetty.websocket.tests.server.jsr356;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.websocket.CloseReason;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseInfo;
import org.eclipse.jetty.websocket.core.WebSocketFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.jsr356.server.JavaxWebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Tests various ways to echo with JSR356
 */
@RunWith(Parameterized.class)
public class JsrEchoTest
{
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/basic")
    public static class EchoBasicTextSocket
    {
        private Session session;
        
        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }
        
        @OnMessage
        public void onText(String msg)
        {
            try
            {
                session.getBasicRemote().sendText(msg);
            }
            catch (IOException esend)
            {
                esend.printStackTrace(System.err);
                try
                {
                    session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(4001), "Unable to echo msg"));
                }
                catch (IOException eclose)
                {
                    eclose.printStackTrace();
                }
            }
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/basic-stateless")
    public static class EchoBasicStatelessTextSocket
    {
        @OnMessage
        public void onText(Session session, String msg)
        {
            try
            {
                session.getBasicRemote().sendText(msg);
            }
            catch (IOException esend)
            {
                esend.printStackTrace(System.err);
                try
                {
                    session.close(new CloseReason(CloseReason.CloseCodes.getCloseCode(4001), "Unable to echo msg"));
                }
                catch (IOException eclose)
                {
                    eclose.printStackTrace();
                }
            }
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/async")
    public static class EchoAsyncTextSocket
    {
        private Session session;
        
        @OnOpen
        public void onOpen(Session session)
        {
            this.session = session;
        }
        
        @OnMessage
        public void onText(String msg)
        {
            session.getAsyncRemote().sendText(msg);
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/async-stateless")
    public static class EchoAsyncStatelessSocket
    {
        @OnMessage
        public void onText(Session session, String msg)
        {
            session.getAsyncRemote().sendText(msg);
        }
    }
    
    @SuppressWarnings("unused")
    @ServerEndpoint("/echo/text/return")
    public static class EchoReturnTextSocket
    {
        @OnMessage
        public String onText(String msg)
        {
            return msg;
        }
    }
    
    private static final List<Class<?>> TESTCLASSES = Arrays.asList(
            EchoBasicTextSocket.class,
            EchoBasicStatelessTextSocket.class,
            EchoAsyncTextSocket.class,
            EchoAsyncStatelessSocket.class,
            EchoReturnTextSocket.class);
    
    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> data()
    {
        List<Object[]> data = new ArrayList<>();
        
        for (Class<?> clazz : TESTCLASSES)
        {
            data.add(new Object[]{clazz.getSimpleName(), clazz});
        }
        
        return data;
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
                ServerContainer container = JavaxWebSocketServerContainerInitializer.configureContext(context);
                
                for (Class<?> clazz : TESTCLASSES)
                {
                    container.addEndpoint(clazz);
                }
            }
        };
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Parameterized.Parameter
    public String endpointClassname;
    
    @Parameterized.Parameter(1)
    public Class<?> endpointClass;
    
    @Test
    public void testTextEcho() throws Exception
    {
        String requestPath = endpointClass.getAnnotation(ServerEndpoint.class).value();
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("Hello Echo"));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("Hello Echo"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        try (LocalFuzzer session = server.newLocalFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
