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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.websocket.DeploymentException;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.jsr356.tests.WSEventTracker;
import org.eclipse.jetty.websocket.jsr356.tests.WSServer;
import org.eclipse.jetty.websocket.jsr356.tests.framehandlers.FrameHandlerTracker;
import org.junit.Rule;
import org.junit.Test;

/**
 * Example of an {@link javax.websocket.Endpoint} extended echo server added programmatically via the
 * {@link javax.websocket.server.ServerContainer#addEndpoint(javax.websocket.server.ServerEndpointConfig)}
 */
public class EndpointViaConfigTest
{
    private static final Logger LOG = Log.getLogger(EndpointViaConfigTest.class);

    @ServerEndpoint("/echo")
    public static class BasicEchoEndpoint extends WSEventTracker implements MessageHandler.Whole<String>
    {
        public void onMessage(String msg)
        {
            super.onWsText(msg);
            // reply with echo
            session.getAsyncRemote().sendText(msg);
        }

        @OnOpen
        public void onOpen(Session session, EndpointConfig config)
        {
            super.onWsOpen(session, config);
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
            javax.websocket.server.ServerContainer container = (javax.websocket.server.ServerContainer) sce.getServletContext().getAttribute(javax.websocket.server.ServerContainer.class.getName());
            if (container == null)
                throw new IllegalStateException("No Websocket ServerContainer in " + sce.getServletContext());

            // Build up a configuration with a specific path
            String path = "/echo";
            ServerEndpointConfig.Builder builder = ServerEndpointConfig.Builder.create(BasicEchoEndpoint.class, path);
            try
            {
                container.addEndpoint(builder.build());
            }
            catch (DeploymentException e)
            {
                throw new RuntimeException("Unable to add endpoint via config file", e);
            }
        }
    }

    @Rule
    public TestingDir testdir = new TestingDir();

    @Test
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir, "app");
        wsb.copyWebInf("basic-echo-endpoint-config-web.xml");
        // the endpoint (extends javax.websocket.Endpoint)
        wsb.copyClass(BasicEchoEndpoint.class);
        // the configuration (adds the endpoint)
        wsb.copyClass(BasicEchoEndpointConfigContextListener.class);

        try
        {
            wsb.start();
            URI uri = wsb.getWsUri();

            WebAppContext webapp = wsb.createWebAppContext();
            wsb.deployWebapp(webapp);

            WebSocketCoreClient client = new WebSocketCoreClient();
            try
            {
                client.start();
                FrameHandlerTracker clientSocket = new FrameHandlerTracker();
                Future<FrameHandler.Channel> clientConnectFuture = client.connect(clientSocket, uri.resolve("/app/echo"));
                // wait for connect
                FrameHandler.Channel channel = clientConnectFuture.get(5, TimeUnit.SECONDS);
                try
                {
                    channel.sendFrame(new TextFrame().setPayload("Hello World"), Callback.NOOP, BatchMode.OFF);

                    String incomingMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
                    assertThat("Expected message", incomingMessage, is("Hello World"));
                }
                finally
                {
                    channel.close(Callback.NOOP);
                }
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
