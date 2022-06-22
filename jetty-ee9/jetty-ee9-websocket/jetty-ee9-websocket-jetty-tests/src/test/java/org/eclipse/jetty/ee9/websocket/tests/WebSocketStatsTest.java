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

package org.eclipse.jetty.ee9.websocket.tests;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.IncludeExcludeConnectionStatistics;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketStatsTest
{
    private final CountDownLatch wsConnectionClosed = new CountDownLatch(1);
    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private IncludeExcludeConnectionStatistics statistics;

    @BeforeEach
    public void start() throws Exception
    {
        statistics = new IncludeExcludeConnectionStatistics();
        statistics.include(WebSocketConnection.class);

        Connection.Listener wsCloseListener = new Connection.Listener()
        {
            @Override
            public void onClosed(Connection connection)
            {
                if (connection instanceof WebSocketConnection)
                    wsConnectionClosed.countDown();
            }
        };

        server = new Server();
        connector = new ServerConnector(server);
        connector.addBean(statistics);
        connector.addBean(wsCloseListener);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        server.setHandler(contextHandler);
        contextHandler.setContextPath("/");
        JettyWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
        {
            container.setAutoFragment(false);
            container.addMapping("/", EchoSocket.class);
        });

        JettyWebSocketServletContainerInitializer.configure(contextHandler, null);
        client = new WebSocketClient();
        client.setAutoFragment(false);

        // Setup JMX.
        MBeanContainer mbeanContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
        server.addBean(mbeanContainer);

        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        server.stop();
        client.stop();
    }

    long getFrameByteSize(Frame frame)
    {
        Generator generator = new Generator();
        ByteBuffer headerBuffer = BufferUtil.allocate(Generator.MAX_HEADER_LENGTH);
        generator.generateHeader(frame, headerBuffer);
        return headerBuffer.remaining() + frame.getPayloadLength();
    }

    @Test
    public void echoStatsTest() throws Exception
    {
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);

        final long numMessages = 1000;
        final String msgText = "hello world";
        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            for (int i = 0; i < numMessages; i++)
            {
                session.getRemote().sendString(msgText);
            }
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(wsConnectionClosed.await(5, TimeUnit.SECONDS));

        assertThat(statistics.getConnectionsMax(), is(1L));
        assertThat(statistics.getConnections(), is(0L));

        // Sent and r eceived all of the echo messages + 1 for the close frame.
        assertThat(statistics.getSentMessages(), is(numMessages + 1L));
        assertThat(statistics.getReceivedMessages(), is(numMessages + 1L));

        Frame textFrame = new Frame(OpCode.TEXT, msgText);
        Frame closeFrame = CloseStatus.NORMAL_STATUS.toFrame();
        final long textFrameSize = getFrameByteSize(textFrame);
        final long closeFrameSize = getFrameByteSize(closeFrame);
        final int maskSize = 4; // We use 4 byte mask for client frames in WSConnection

        final long expectedSent =  numMessages * textFrameSize + closeFrameSize;
        final long expectedReceived =  numMessages * (textFrameSize + maskSize) + (closeFrameSize + maskSize);

        assertThat(statistics.getSentBytes(), is(expectedSent));
        assertThat(statistics.getReceivedBytes(), is(expectedReceived));
    }
}
