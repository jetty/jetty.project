//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.net.URI;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.api.extensions.ExtensionFactory;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.client.io.WebSocketClientConnection;
import org.eclipse.jetty.websocket.common.extensions.ExtensionStack;
import org.eclipse.jetty.websocket.common.extensions.compress.DeflateFrameExtension;
import org.eclipse.jetty.websocket.jsr356.JsrExtension;
import org.eclipse.jetty.websocket.jsr356.JsrSession;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.BasicEchoEndpoint;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ExtensionStackProcessingTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketContainer client;
    private ServletContextHandler servletContextHandler;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        servletContextHandler = new ServletContextHandler(server, "/", true, false);
        ServerContainer container = WebSocketServerContainerInitializer.configureContext(servletContextHandler);

        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(BasicEchoEndpoint.class, "/").build();
        container.addEndpoint(config);

        client = ContainerProvider.getWebSocketContainer();

        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    private void assumeDeflateFrameAvailable()
    {
        NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration)servletContextHandler
            .getServletContext().getAttribute(NativeWebSocketConfiguration.class.getName());
        ExtensionFactory serverExtensionFactory = configuration.getFactory().getExtensionFactory();
        assumeTrue(serverExtensionFactory.isAvailable("permessage-deflate"), "Server has permessage-deflate extension registered");
    }

    @Test
    public void testDeflateFrameExtension() throws Exception
    {
        assumeDeflateFrameAvailable();

        ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
            .extensions(Arrays.asList(new JsrExtension("deflate-frame")))
            .build();

        final String content = "deflate_me";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        Session session = client.connectToServer(new EndpointAdapter()
        {
            @Override
            public void onMessage(String message)
            {
                assertEquals(content, message);
                messageLatch.countDown();
            }
        }, config, uri);

        // Make sure everything is wired properly.
        OutgoingFrames firstOut = ((JsrSession)session).getOutgoingHandler();
        assertTrue(firstOut instanceof ExtensionStack);
        ExtensionStack extensionStack = (ExtensionStack)firstOut;
        assertTrue(extensionStack.isRunning());
        OutgoingFrames secondOut = extensionStack.getNextOutgoing();
        assertTrue(secondOut instanceof DeflateFrameExtension);
        DeflateFrameExtension deflateExtension = (DeflateFrameExtension)secondOut;
        assertTrue(deflateExtension.isRunning());
        OutgoingFrames thirdOut = deflateExtension.getNextOutgoing();
        assertTrue(thirdOut instanceof WebSocketClientConnection);

        final CountDownLatch latch = new CountDownLatch(1);
        session.getAsyncRemote().sendText(content, result -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPerMessageDeflateExtension() throws Exception
    {
        assumeDeflateFrameAvailable();

        ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
            .extensions(Arrays.asList(new JsrExtension("permessage-deflate")))
            .build();

        final String content = "deflate_me";
        final CountDownLatch messageLatch = new CountDownLatch(1);
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        Session session = client.connectToServer(new EndpointAdapter()
        {
            @Override
            public void onMessage(String message)
            {
                assertEquals(content, message);
                messageLatch.countDown();
            }
        }, config, uri);

        final CountDownLatch latch = new CountDownLatch(1);
        session.getAsyncRemote().sendText(content, result -> latch.countDown());

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(messageLatch.await(5, TimeUnit.SECONDS));
    }

    private abstract static class EndpointAdapter extends Endpoint implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }
    }
}
