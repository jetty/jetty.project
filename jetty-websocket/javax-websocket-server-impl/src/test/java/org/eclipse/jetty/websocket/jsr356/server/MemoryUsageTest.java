//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.lessThan;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.BasicEchoEndpoint;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MemoryUsageTest
{
    private Server server;
    private ServerConnector connector;
    private WebSocketContainer client;

    @Before
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(server, "/", true, false);
        ServerContainer container = WebSocketServerContainerInitializer.configureContext(context);
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(BasicEchoEndpoint.class, "/").build();
        container.addEndpoint(config);

        server.start();

        client = ContainerProvider.getWebSocketContainer();
        server.addBean(client, true);
    }

    @After
    public void dispose() throws Exception
    {
        server.stop();
    }

    @SuppressWarnings("unused")
    @Test
    public void testMemoryUsage() throws Exception
    {
        int sessionCount = 1000;
        Session[] sessions = new Session[sessionCount];

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

        System.gc();
        MemoryUsage heapBefore = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapBefore = memoryMXBean.getNonHeapMemoryUsage();

        URI uri = URI.create("ws://localhost:" + connector.getLocalPort());
        final CountDownLatch latch = new CountDownLatch(sessionCount);
        for (int i = 0; i < sessionCount; ++i)
        {
            sessions[i] = client.connectToServer(new EndpointAdapter()
            {
                @Override
                public void onMessage(String message)
                {
                    latch.countDown();
                }
            }, uri);
        }
        for (int i = 0; i < sessionCount; ++i)
        {
            sessions[i].getBasicRemote().sendText("OK");
        }
        latch.await(5 * sessionCount, TimeUnit.MILLISECONDS);

        System.gc();
        MemoryUsage heapAfter = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapAfter = memoryMXBean.getNonHeapMemoryUsage();

        long heapUsed = heapAfter.getUsed() - heapBefore.getUsed();
        long nonHeapUsed = nonHeapAfter.getUsed() - nonHeapBefore.getUsed();

        System.out.println("heapUsed = " + heapUsed);
        System.out.println("nonHeapUsed = " + nonHeapUsed);
//        new CountDownLatch(1).await();

        // Assume no more than 25 KiB per session pair (client and server).
        long expected = 25 * 1024 * sessionCount;
        Assert.assertThat("heap used", heapUsed,lessThan(expected));
    }

    private static abstract class EndpointAdapter extends Endpoint implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }
    }
}
