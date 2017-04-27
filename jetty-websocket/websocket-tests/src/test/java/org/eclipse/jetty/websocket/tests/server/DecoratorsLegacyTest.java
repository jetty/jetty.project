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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContext;

import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.Decorator;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
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

public class DecoratorsLegacyTest
{
    private static class DecoratorsSocket extends WebSocketAdapter
    {
        private final DecoratedObjectFactory objFactory;
        
        public DecoratorsSocket(DecoratedObjectFactory objFactory)
        {
            this.objFactory = objFactory;
        }
        
        @Override
        public void onWebSocketText(String message)
        {
            StringWriter str = new StringWriter();
            PrintWriter out = new PrintWriter(str);
            
            if (objFactory != null)
            {
                out.printf("Object is a DecoratedObjectFactory%n");
                List<Decorator> decorators = objFactory.getDecorators();
                out.printf("Decorators.size = [%d]%n", decorators.size());
                for (Decorator decorator : decorators)
                {
                    out.printf(" decorator[] = %s%n", decorator.getClass().getName());
                }
            }
            else
            {
                out.printf("DecoratedObjectFactory is NULL%n");
            }
            
            getRemote().sendStringByFuture(str.toString());
        }
    }
    
    private static class DecoratorsCreator implements WebSocketCreator
    {
        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            ServletContext servletContext = req.getHttpServletRequest().getServletContext();
            DecoratedObjectFactory objFactory = (DecoratedObjectFactory) servletContext.getAttribute(DecoratedObjectFactory.ATTR);
            return new DecoratorsSocket(objFactory);
        }
    }
    
    public static class DecoratorsRequestServlet extends WebSocketServlet
    {
        private static final long serialVersionUID = 1L;
        private final WebSocketCreator creator;
        
        public DecoratorsRequestServlet(WebSocketCreator creator)
        {
            this.creator = creator;
        }
        
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.setCreator(this.creator);
        }
    }
    
    @SuppressWarnings("deprecation")
    private static class DummyLegacyDecorator implements org.eclipse.jetty.servlet.ServletContextHandler.Decorator
    {
        @Override
        public <T> T decorate(T o)
        {
            return o;
        }
        
        @Override
        public void destroy(Object o)
        {
        }
    }
    
    private static SimpleServletServer server;
    private static DecoratorsCreator decoratorsCreator;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        decoratorsCreator = new DecoratorsCreator();
        server = new SimpleServletServer(new DecoratorsRequestServlet(decoratorsCreator))
        {
            @SuppressWarnings("deprecation")
            @Override
            protected void configureServletContextHandler(ServletContextHandler context)
            {
                context.getObjectFactory().clear();
                // Add decorator in the legacy way
                context.addDecorator(new DummyLegacyDecorator());
            }
        };
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Rule
    public TestName testname = new TestName();
    
    private WebSocketClient client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    @Test
    public void testAccessRequestCookies() throws Exception
    {
        client.setMaxIdleTimeout(TimeUnit.SECONDS.toMillis(1));
        
        URI wsUri = server.getServerUri();
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // Request Info
        clientSession.getRemote().sendString("info");
        
        // Read message
        String incomingMsg = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("DecoratedObjectFactory", incomingMsg, containsString("Object is a DecoratedObjectFactory"));
        assertThat("decorators.size", incomingMsg, containsString("Decorators.size = [1]"));
        assertThat("decorator type", incomingMsg, containsString("decorator[] = " + DummyLegacyDecorator.class.getName()));
        
        clientSession.close();
    }
}
