//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.extensions.WebSocketExtensionFactory;
import org.eclipse.jetty.websocket.server.NativeWebSocketConfiguration;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer.ATTR_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketCompressionPoolTest
{
    private Server server;
    private WebSocketClient client;
    private ServletContextHandler context1;
    private ServletContextHandler context2;

    @BeforeEach
    public void start() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        context1 = new ServletContextHandler();
        context1.setContextPath("/context1");
        NativeWebSocketServletContainerInitializer.configure(context1, (context, container) ->
            container.addMapping("/", EchoSocket.class));
        WebSocketUpgradeFilter.configure(context1);

        context2 = new ServletContextHandler();
        context2.setContextPath("/context2");
        NativeWebSocketServletContainerInitializer.configure(context2, (context, container) ->
            container.addMapping("/", EchoSocket.class));
        WebSocketUpgradeFilter.configure(context2);

        ContextHandlerCollection contextHandlerCollection = new ContextHandlerCollection();
        contextHandlerCollection.addHandler(context1);
        contextHandlerCollection.addHandler(context2);
        server.setHandler(contextHandlerCollection);

        client = new WebSocketClient();

        server.setDumpAfterStart(true);
        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        server.stop();
        client.stop();
    }

    public static WebSocketExtensionFactory getExtensionFactory(ContextHandler contextHandler)
    {
        NativeWebSocketConfiguration configuration = (NativeWebSocketConfiguration)contextHandler.getAttribute(ATTR_KEY);
        assertNotNull(configuration);
        WebSocketExtensionFactory extensionFactory = configuration.getFactory().getBean(WebSocketExtensionFactory.class);
        assertNotNull(extensionFactory);
        return extensionFactory;
    }

    @Test
    public void test() throws Exception
    {
        // Check the two contexts are sharing the same inflater/deflater pools.
        WebSocketExtensionFactory extensionFactory1 = getExtensionFactory(context1);
        WebSocketExtensionFactory extensionFactory2 = getExtensionFactory(context2);
        assertThat(extensionFactory1.getInflaterPool(), is(extensionFactory2.getInflaterPool()));
        assertThat(extensionFactory1.getDeflaterPool(), is(extensionFactory2.getDeflaterPool()));

        // The extension factories and the pools have been started.
        assertTrue(extensionFactory1.isStarted());
        assertTrue(extensionFactory2.isStarted());
        assertTrue(extensionFactory1.getInflaterPool().isStarted());
        assertTrue(extensionFactory1.getDeflaterPool().isStarted());

        // Pools are managed by the server.
        assertTrue(server.isManaged(extensionFactory1.getInflaterPool()));
        assertTrue(server.isManaged(extensionFactory1.getDeflaterPool()));
    }
}
