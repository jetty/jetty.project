/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/

package org.eclipse.jetty.websocket.server;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocket.Connection;
import org.eclipse.jetty.websocket.WebSocket.OnTextMessage;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

public class WebSocketRedeployTest
{
    private Server server;
    private ServletContextHandler context;
    private String uri;
    private WebSocketClientFactory wsFactory;

    @After
    public void destroy() throws Exception
    {
        if (wsFactory != null)
        {
            wsFactory.stop();
        }
        if (server != null)
        {
            server.stop();
            server.join();
        }
    }

    public void init(final WebSocket webSocket) throws Exception
    {
        server = new Server();
        SelectChannelConnector connector = new SelectChannelConnector();
//        connector.setPort(8080);
        server.addConnector(connector);

        HandlerCollection handlers = new HandlerCollection();
        server.setHandler(handlers);

        String contextPath = "/test_context";
        context = new ServletContextHandler(handlers, contextPath, ServletContextHandler.SESSIONS);

        WebSocketServlet servlet = new WebSocketServlet()
        {
            public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
            {
                return webSocket;
            }
        };
        String servletPath = "/websocket";
        context.addServlet(new ServletHolder(servlet), servletPath);

        server.start();

        uri = "ws://localhost:" + connector.getLocalPort() + contextPath + servletPath;

        wsFactory = new WebSocketClientFactory();
        wsFactory.start();
    }

    @Test
    public void testStoppingClientFactoryClosesConnections() throws Exception
    {
        final CountDownLatch openLatch = new CountDownLatch(2);
        final CountDownLatch closeLatch = new CountDownLatch(2);
        init(new WebSocket.OnTextMessage()
        {
            @Override
            public void onClose(int closeCode, String message)
            {
                closeLatch.countDown();
            }

            @Override
            public void onMessage(String data)
            {
            }

            @Override
            public void onOpen(Connection connection)
            {
                openLatch.countDown();
            }
        });

        WebSocketClient client = wsFactory.newWebSocketClient();
        client.open(new URI(uri), new WebSocket.OnTextMessage()
        {
            @Override
            public void onClose(int closeCode, String message)
            {
                closeLatch.countDown();
            }

            @Override
            public void onMessage(String data)
            {
            }

            @Override
            public void onOpen(Connection connection)
            {
                openLatch.countDown();
            }
        }, 5, TimeUnit.SECONDS);

        Assert.assertTrue(openLatch.await(5, TimeUnit.SECONDS));

        wsFactory.stop();

        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testStoppingContextClosesConnections() throws Exception
    {
        final CountDownLatch openLatch = new CountDownLatch(2);
        final CountDownLatch closeLatch = new CountDownLatch(2);
        init(new WebSocket.OnTextMessage()
        {
            @Override
            public void onClose(int closeCode, String message)
            {
                closeLatch.countDown();
            }

            @Override
            public void onMessage(String data)
            {
            }

            @Override
            public void onOpen(Connection connection)
            {
                openLatch.countDown();
            }
        });

        WebSocketClient client = wsFactory.newWebSocketClient();
        client.open(new URI(uri), new WebSocket.OnTextMessage()
        {
            @Override
            public void onClose(int closeCode, String message)
            {
                closeLatch.countDown();
            }

            @Override
            public void onMessage(String data)
            {
            }

            @Override
            public void onOpen(Connection connection)
            {
                openLatch.countDown();
            }
        }, 5, TimeUnit.SECONDS);

        Assert.assertTrue(openLatch.await(5, TimeUnit.SECONDS));

        context.stop();

        Assert.assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }
}
