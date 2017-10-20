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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class UntrustedWSClientTest
{
    @WebSocket
    public static class StatusSocket
    {
        @OnWebSocketMessage
        public void onMessage(Session session, String msg) throws IOException
        {
            session.getRemote().sendString(msg);
        }
    }
    
    public static class StatusServlet extends WebSocketServlet implements WebSocketCreator
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this);
        }
        
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            return new StatusSocket();
        }
    }
    
    private static Server server;
    private static URI wsServerURI;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server(0);
        
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        
        context.addServlet(StatusServlet.class, "/status");
        
        server.setHandler(context);
        server.start();
        
        URI serverURI = server.getURI();
        wsServerURI = WSURI.toWebsocket(serverURI);
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testConnect() throws Exception
    {
        UntrustedWSClient client = new UntrustedWSClient();
        try
        {
            client.start();
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            Future<UntrustedWSSession> fut = client.connect(wsServerURI.resolve("/status"), request);
            try (UntrustedWSSession session = fut.get(5, TimeUnit.SECONDS))
            {
                UntrustedWSEndpoint endpoint = session.getUntrustedEndpoint();
                
                session.getRemote().sendString("hello");
                
                String message = endpoint.messageQueue.poll(5, TimeUnit.SECONDS);
                assertThat("message", message, is("hello"));
                // TODO: test close
            }
        }
        finally
        {
            client.stop();
        }
    }
}
