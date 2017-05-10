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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.ContainerProvider;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.HandshakeResponse;
import javax.websocket.MessageHandler;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.PongMessage;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerContainer;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.tests.WSServer;
import org.eclipse.jetty.websocket.tests.client.jsr356.JsrClientTrackingSocket;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class PingPongTest
{
    @ServerEndpoint(value="/pong-socket", configurator=PongContextListener.Config.class)
    public static class PongSocket
    {
        private static final Logger LOG = Log.getLogger(PongSocket.class);
        private String path = "?";
        private Session session;
        
        @OnOpen
        public void onOpen(Session session, EndpointConfig config)
        {
            this.session = session;
            this.path = (String)config.getUserProperties().get("path");
        }
        
        @OnMessage
        public void onPong(PongMessage pong)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("PongSocket.onPong(): PongMessage.appData={}",BufferUtil.toDetailString(pong.getApplicationData()));
            byte buf[] = BufferUtil.toArray(pong.getApplicationData());
            String message = new String(buf, StandardCharsets.UTF_8);
            this.session.getAsyncRemote().sendText("PongSocket.onPong(PongMessage)[" + path + "]:" + message);
        }
    }
    
    public static class PongMessageEndpoint extends Endpoint implements MessageHandler.Whole<PongMessage>
    {
        private String path = "?";
        private Session session;
        
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            this.session = session;
            this.session.addMessageHandler(this);
            this.path = (String)config.getUserProperties().get("path");
        }
        
        @Override
        public void onMessage(PongMessage pong)
        {
            byte buf[] = BufferUtil.toArray(pong.getApplicationData());
            String message = new String(buf,StandardCharsets.UTF_8);
            this.session.getAsyncRemote().sendText("PongMessageEndpoint.onMessage(PongMessage):[" + path + "]:" + message);
        }
    }
    
    public static class PongContextListener implements ServletContextListener
    {
        public static class Config extends ServerEndpointConfig.Configurator
        {
            @Override
            public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response)
            {
                sec.getUserProperties().put("path",sec.getPath());
                super.modifyHandshake(sec,request,response);
            }
        }
        
        @Override
        public void contextDestroyed(ServletContextEvent sce)
        {
        /* do nothing */
        }
        
        @Override
        public void contextInitialized(ServletContextEvent sce)
        {
            ServerContainer container = (ServerContainer)sce.getServletContext().getAttribute(ServerContainer.class.getName());
            try
            {
                ServerEndpointConfig.Configurator config = new Config();
                container.addEndpoint(ServerEndpointConfig.Builder.create(PongMessageEndpoint.class,"/pong").configurator(config).build());
            }
            catch (DeploymentException e)
            {
                throw new RuntimeException("Unable to add endpoint directly",e);
            }
        }
    }
    
    private static WSServer server;
    private static URI serverUri;
    private static WebSocketContainer client;

    @BeforeClass
    public static void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(PingPongTest.class.getName());
        server = new WSServer(testdir,"app");
        server.copyWebInf("pong-config-web.xml");

        server.copyClass(PongContextListener.class);
        server.copyClass(PongMessageEndpoint.class);
        server.copyClass(PongSocket.class);

        server.start();
        serverUri = server.getServerUri();

        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }

    @BeforeClass
    public static void startClient() throws Exception
    {
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    private void assertEcho(String endpointPath, Consumer<Session> sendAction, String ... expectedMsgs) throws Exception
    {
        JsrClientTrackingSocket socket = new JsrClientTrackingSocket();
        Session session = null;
        URI toUri = serverUri.resolve(endpointPath);
    
        try
        {
            // Connect
            session = client.connectToServer(socket,toUri);
            socket.openLatch.await(2, TimeUnit.SECONDS);
        
            // Apply send action
            sendAction.accept(session);
        
            // Validate Responses
            for(int i=0; i<expectedMsgs.length; i++)
            {
                String pingMsg = socket.messageQueue.poll(1, TimeUnit.SECONDS);
                assertThat("Expected message[" + i + "]", pingMsg, containsString(expectedMsgs[i]));
            }
        }
        finally
        {
            if (session != null)
            {
                // Close
                session.close();
            }
        }
    }

    @Test(timeout = 6000)
    public void testPongEndpoint() throws Exception
    {
        assertEcho("/app/pong", (session) -> {
            try
            {
                session.getBasicRemote().sendPong(BufferUtil.toBuffer("hello", UTF_8));
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }, "PongMessageEndpoint.onMessage(PongMessage):[/pong]:hello");
    }
    
    @Test(timeout = 6000)
    public void testPongSocket() throws Exception
    {
        assertEcho("/app/pong-socket", (session) -> {
            try
            {
                session.getBasicRemote().sendPong(BufferUtil.toBuffer("hello", UTF_8));
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }, "PongSocket.onPong(PongMessage)[/pong-socket]:hello");
    }
}
