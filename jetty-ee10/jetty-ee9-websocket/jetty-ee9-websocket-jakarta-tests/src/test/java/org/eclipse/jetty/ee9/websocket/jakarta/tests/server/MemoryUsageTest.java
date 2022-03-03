//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

public class MemoryUsageTest
{
    public static class BasicEndpoint extends Endpoint implements MessageHandler.Whole<String>
    {
        private jakarta.websocket.Session session;

        @Override
        public void onMessage(String msg)
        {
            // reply with echo
            session.getAsyncRemote().sendText(msg);
        }

        @Override
        public void onOpen(jakarta.websocket.Session session, EndpointConfig config)
        {
            this.session = session;
            this.session.addMessageHandler(this);
        }
    }

    private Server server;
    private ServerConnector connector;
    private WebSocketContainer client;

    @BeforeEach
    public void prepare() throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(server, "/", true, false);
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            ServerEndpointConfig config = ServerEndpointConfig.Builder.create(BasicEndpoint.class, "/").build();
            container.addEndpoint(config);
        });

        server.start();

        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        server.stop();
    }

    @SuppressWarnings("unused")
    @Test
    @EnabledOnJre(JRE.JAVA_8)
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

        // Assume no more than 25 KiB per session pair (client and server).
        long expected = 25 * 1024 * sessionCount;
        assertThat("heap used", heapUsed, lessThan(expected));
    }

    public abstract static class EndpointAdapter extends Endpoint implements MessageHandler.Whole<String>
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
        }
    }
}
