//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;
import org.eclipse.jetty.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebSocketStatsTest
{
    public static class MyWebSocketServlet extends JettyWebSocketServlet
    {
        @Override
        public void configure(JettyWebSocketServletFactory factory)
        {
            factory.setAutoFragment(false);
            factory.addMapping("/", (req, resp) -> new EchoSocket());
        }
    }

    private Server server;
    private ServerConnector connector;
    private WebSocketClient client;
    private ConnectionStatistics statistics;
    private final CountDownLatch wsUpgradeComplete = new CountDownLatch(1);
    private final CountDownLatch wsConnectionClosed = new CountDownLatch(1);

    @BeforeEach
    public void start() throws Exception
    {
        statistics = new ConnectionStatistics()
        {
            @Override
            public void onClosed(Connection connection)
            {
                super.onClosed(connection);

                if (connection instanceof WebSocketConnection)
                    wsConnectionClosed.countDown();
                else if (connection instanceof HttpConnection)
                    wsUpgradeComplete.countDown();
            }
        };

        server = new Server();
        connector = new ServerConnector(server);
        connector.addBean(statistics);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        contextHandler.setContextPath("/");
        contextHandler.addServlet(MyWebSocketServlet.class, "/testPath");
        server.setHandler(contextHandler);

        JettyWebSocketServletContainerInitializer.configure(contextHandler, null);
        client = new WebSocketClient();

        server.start();
        client.start();
    }

    @AfterEach
    public void stop() throws Exception
    {
        client.stop();
        server.stop();
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
        URI uri = URI.create("ws://localhost:" + connector.getLocalPort() + "/testPath");
        EventSocket socket = new EventSocket();
        CompletableFuture<Session> connect = client.connect(socket, uri);

        final long numMessages = 1000;
        final String msgText = "hello world";

        long upgradeSentBytes;
        long upgradeReceivedBytes;

        try (Session session = connect.get(5, TimeUnit.SECONDS))
        {
            wsUpgradeComplete.await(5, TimeUnit.SECONDS);
            upgradeSentBytes = statistics.getSentBytes();
            upgradeReceivedBytes = statistics.getReceivedBytes();

            for (int i = 0; i < numMessages; i++)
            {
                session.getRemote().sendString(msgText);
            }
        }
        assertTrue(socket.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(wsConnectionClosed.await(5, TimeUnit.SECONDS));

        assertThat(statistics.getConnectionsMax(), is(1L));
        assertThat(statistics.getConnections(), is(0L));

        assertThat(statistics.getSentMessages(), is(numMessages + 2L));
        assertThat(statistics.getReceivedMessages(), is(numMessages + 2L));

        Frame textFrame = new Frame(OpCode.TEXT, msgText);
        Frame closeFrame = CloseStatus.NORMAL_STATUS.toFrame();

        final long textFrameSize = getFrameByteSize(textFrame);
        final long closeFrameSize = getFrameByteSize(closeFrame);
        final int maskSize = 4; // We use 4 byte mask for client frames in WSConnection

        final long expectedSent = upgradeSentBytes + numMessages * textFrameSize + closeFrameSize;
        final long expectedReceived = upgradeReceivedBytes + numMessages * (textFrameSize + maskSize) + closeFrameSize + maskSize;

        assertThat(statistics.getSentBytes(), is(expectedSent));
        assertThat(statistics.getReceivedBytes(), is(expectedReceived));
    }
}
