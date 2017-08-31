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

import static org.hamcrest.Matchers.anything;

import java.net.URI;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.listeners.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.impl.WebSocketClientImpl;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Testing badly behaving Socket class implementations to get the best
 * close states and behaviors out of the websocket implementation.
 */
public class MisbehavingClassTest
{
    @WebSocket
    public static class AnnotatedRuntimeOnConnectSocket
    {
        public LinkedList<Throwable> errors = new LinkedList<>();
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public int closeStatusCode;
        public String closeReason;
        
        @OnWebSocketConnect
        public void onWebSocketConnect(Session sess)
        {
            // Intentional runtime exception.
            throw new RuntimeException("Intentional Exception from onWebSocketConnect");
        }
        
        @OnWebSocketClose
        public void onWebSocketClose(int statusCode, String reason)
        {
            closeLatch.countDown();
            closeStatusCode = statusCode;
            closeReason = reason;
        }
        
        @OnWebSocketError
        public void onWebSocketError(Throwable cause)
        {
            this.errors.add(cause);
        }
        
        public void reset()
        {
            this.closeLatch = new CountDownLatch(1);
            this.closeStatusCode = -1;
            this.closeReason = null;
            this.errors.clear();
        }
    }
    
    public static class ListenerRuntimeOnConnectSocket extends WebSocketAdapter
    {
        public LinkedList<Throwable> errors = new LinkedList<>();
        public CountDownLatch closeLatch = new CountDownLatch(1);
        public int closeStatusCode;
        public String closeReason;
        
        @Override
        public void onWebSocketConnect(Session sess)
        {
            super.onWebSocketConnect(sess);
            
            throw new RuntimeException("Intentional Exception from onWebSocketConnect");
        }
        
        @Override
        public void onWebSocketClose(int statusCode, String reason)
        {
            closeLatch.countDown();
            closeStatusCode = statusCode;
            closeReason = reason;
        }
        
        @Override
        public void onWebSocketError(Throwable cause)
        {
            this.errors.add(cause);
        }
        
        @Override
        public void onWebSocketText(String message)
        {
            getRemote().sendStringByFuture(message);
        }
        
        public void reset()
        {
            this.closeLatch = new CountDownLatch(1);
            this.closeStatusCode = -1;
            this.closeReason = null;
            this.errors.clear();
        }
    }
    
    public static class BadSocketsServlet extends WebSocketServlet implements WebSocketCreator
    {
        public ListenerRuntimeOnConnectSocket listenerRuntimeConnect;
        public AnnotatedRuntimeOnConnectSocket annotatedRuntimeConnect;
        
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this);
            
            this.listenerRuntimeConnect = new ListenerRuntimeOnConnectSocket();
            this.annotatedRuntimeConnect = new AnnotatedRuntimeOnConnectSocket();
        }
        
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            if (req.hasSubProtocol("listener-runtime-connect"))
            {
                return this.listenerRuntimeConnect;
            }
            else if (req.hasSubProtocol("annotated-runtime-connect"))
            {
                return this.annotatedRuntimeConnect;
            }
            
            return null;
        }
    }
    
    private static SimpleServletServer server;
    private static BadSocketsServlet badSocketsServlet;

    @BeforeClass
    public static void startServer() throws Exception
    {
        badSocketsServlet = new BadSocketsServlet();
        server = new SimpleServletServer(badSocketsServlet);
        server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Rule
    public TestName testname = new TestName();
    
    private WebSocketClientImpl client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClientImpl();
        client.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @Test
    public void testListenerRuntimeOnConnect() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(ListenerRuntimeOnConnectSocket.class))
        {
            ListenerRuntimeOnConnectSocket socket = badSocketsServlet.listenerRuntimeConnect;
            socket.reset();
    
            client.setMaxIdleTimeout(TimeUnit.SECONDS.toMillis(1));
            URI wsUri = server.getServerUri();
    
            TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
            ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
            upgradeRequest.setSubProtocols("listener-runtime-connect");
            Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
    
            Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    
            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.SERVER_ERROR, anything());
            
            clientSession.close();
        }
    }
    
    @Test
    public void testAnnotatedRuntimeOnConnect() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(AnnotatedRuntimeOnConnectSocket.class))
        {
            AnnotatedRuntimeOnConnectSocket socket = badSocketsServlet.annotatedRuntimeConnect;
            socket.reset();

            client.setMaxIdleTimeout(TimeUnit.SECONDS.toMillis(1));
            URI wsUri = server.getServerUri();
    
            TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
            ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
            upgradeRequest.setSubProtocols("annotated-runtime-connect");
            Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
    
            Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    
            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.SERVER_ERROR, anything());
    
            clientSession.close();
        }
    }
}
