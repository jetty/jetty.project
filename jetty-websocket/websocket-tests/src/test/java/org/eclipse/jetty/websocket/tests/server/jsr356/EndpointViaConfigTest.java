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

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.jsr356.server.ServerContainer;
import org.eclipse.jetty.websocket.tests.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.WSServer;
import org.eclipse.jetty.websocket.tests.jsr356.AbstractJsrTrackingEndpoint;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Example of an {@link javax.websocket.Endpoint} extended echo server added programmatically via the
 * {@link ServerContainer#addEndpoint(javax.websocket.server.ServerEndpointConfig)}
 */
public class EndpointViaConfigTest
{
    private static final Logger LOG = Log.getLogger(EndpointViaConfigTest.class);
    
    public static class BasicEchoEndpoint extends AbstractJsrTrackingEndpoint implements MessageHandler.Whole<String>
    {
        @Override
        public void onMessage(String msg)
        {
            super.onWsText(msg);
            // reply with echo
            session.getAsyncRemote().sendText(msg);
        }
        
        @Override
        public void onOpen(javax.websocket.Session session, EndpointConfig config)
        {
            super.onOpen(session, config);
            this.session.addMessageHandler(this);
        }
    }
    
    public static class BasicEchoEndpointConfigContextListener implements ServletContextListener
    {
        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
        /* do nothing */
        }
        
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            javax.websocket.server.ServerContainer container = (javax.websocket.server.ServerContainer)sce.getServletContext().getAttribute(javax.websocket.server.ServerContainer.class.getName());
            if (container==null)
                throw new IllegalStateException("No Websocket ServerContainer in "+sce.getServletContext());
            
            // Build up a configuration with a specific path
            String path = "/echo";
            ServerEndpointConfig.Builder builder = ServerEndpointConfig.Builder.create(BasicEchoEndpoint.class,path);
            try
            {
                container.addEndpoint(builder.build());
            }
            catch (DeploymentException e)
            {
                throw new RuntimeException("Unable to add endpoint via config file",e);
            }
        }
    }
    
    @Rule
    public TestingDir testdir = new TestingDir();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    @Test
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir,"app");
        wsb.copyWebInf("basic-echo-endpoint-config-web.xml");
        // the endpoint (extends javax.websocket.Endpoint)
        wsb.copyClass(BasicEchoEndpoint.class);
        // the configuration (adds the endpoint)
        wsb.copyClass(BasicEchoEndpointConfigContextListener.class);

        try
        {
            wsb.start();
            URI uri = wsb.getServerUri();

            WebAppContext webapp = wsb.createWebAppContext();
            wsb.deployWebapp(webapp);

            WebSocketClient client = new WebSocketClient(bufferPool);
            try
            {
                client.start();
                TrackingEndpoint clientSocket = new TrackingEndpoint("Client");
                Future<Session> clientConnectFuture = client.connect(clientSocket,uri.resolve("/app/echo"));
                // wait for connect
                Session clientSession = clientConnectFuture.get(5,TimeUnit.SECONDS);
                clientSession.getRemote().sendString("Hello World");
                
                String incomingMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
                Assert.assertEquals("Expected message","Hello World",incomingMessage);
                
                clientSession.close();
                clientSocket.awaitCloseEvent("Client");
            }
            finally
            {
                client.stop();
                LOG.debug("Stopped - " + client);
            }
        }
        finally
        {
            wsb.stop();
            LOG.debug("Stopped - " + wsb);
        }
    }
}
