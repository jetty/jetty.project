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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.websocket.jakarta.common.JakartaWebSocketSession;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.DataUtils;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.Fuzzer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.WSEndpointTracker;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TextStreamTest
{
    private static final Logger LOG = LoggerFactory.getLogger(TextStreamTest.class);
    private static final BlockingArrayQueue<QueuedTextStreamer> serverEndpoints = new BlockingArrayQueue<>();

    private final ClientEndpointConfig clientConfig = ClientEndpointConfig.Builder.create().build();
    private LocalServer server;
    private ServerContainer container;
    private WebSocketContainer wsClient;

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        container = server.getServerContainer();
        container.addEndpoint(ServerTextStreamer.class);
        container.addEndpoint(ServerEndpointConfig.Builder.create(QueuedTextStreamer.class, "/test").build());
        container.addEndpoint(ServerEndpointConfig.Builder.create(QueuedPartialTextStreamer.class, "/partial").build());

        wsClient = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testWith1kMessage() throws Exception
    {
        testEcho(1024);
    }

    private byte[] newData(int size)
    {
        @SuppressWarnings("SpellCheckingInspection")
        byte[] pattern = "01234567890abcdefghijlklmopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++)
        {
            data[i] = pattern[i % pattern.length];
        }
        return data;
    }

    private void testEcho(int size) throws Exception
    {
        byte[] data = newData(size);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(ByteBuffer.wrap(data)));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        ByteBuffer expectedMessage = DataUtils.copyOf(data);
        try (Fuzzer fuzzer = server.newNetworkFuzzer("/echo"))
        {
            fuzzer.sendBulk(send);
            BlockingQueue<Frame> receivedFrames = fuzzer.getOutputFrames();
            fuzzer.expectMessage(receivedFrames, OpCode.TEXT, expectedMessage);
            fuzzer.expect(List.of(CloseStatus.toFrame(CloseStatus.NORMAL)));
        }
    }

    @Test
    public void testAtMaxDefaultMessageBufferSize() throws Exception
    {
        testEcho(container.getDefaultMaxTextMessageBufferSize());
    }

    @Test
    public void testLargerThenMaxDefaultMessageBufferSize() throws Exception
    {
        int maxTextMessageBufferSize = container.getDefaultMaxTextMessageBufferSize();
        int size = maxTextMessageBufferSize + 16;
        byte[] data = newData(size);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT).setPayload(ByteBuffer.wrap(data)));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        // make copy of raw data (to avoid client masking during send)
        byte[] expectedData = new byte[data.length];
        System.arraycopy(data, 0, expectedData, 0, data.length);

        try (Fuzzer fuzzer = server.newNetworkFuzzer("/echo"))
        {
            fuzzer.sendBulk(send);

            BlockingQueue<Frame> receivedFrames = fuzzer.getOutputFrames();
            fuzzer.expectMessage(receivedFrames, OpCode.TEXT, ByteBuffer.wrap(expectedData));
            fuzzer.expect(List.of(CloseStatus.toFrame(CloseStatus.NORMAL)));
        }
    }

    @Test
    public void testMessageOrdering() throws Exception
    {
        ClientTextStreamer client = new ClientTextStreamer();
        Session session = wsClient.connectToServer(client, clientConfig, server.getWsUri().resolve("/test"));

        final int numLoops = 20;
        for (int i = 0; i < numLoops; i++)
        {
            session.getBasicRemote().sendText(Integer.toString(i));
        }
        session.close();

        QueuedTextStreamer queuedTextStreamer = serverEndpoints.poll(5, TimeUnit.SECONDS);
        assertNotNull(queuedTextStreamer);
        for (int i = 0; i < numLoops; i++)
        {
            String msg = queuedTextStreamer.messages.poll(5, TimeUnit.SECONDS);
            assertThat(msg, Matchers.is(Integer.toString(i)));
        }
    }

    @Test
    public void testFragmentedMessageOrdering() throws Exception
    {
        ClientTextStreamer client = new ClientTextStreamer();
        Session session = wsClient.connectToServer(client, clientConfig, server.getWsUri().resolve("/test"));

        final int numLoops = 20;
        for (int i = 0; i < numLoops; i++)
        {
            session.getBasicRemote().sendText("firstFrame" + i, false);
            session.getBasicRemote().sendText("|secondFrame" + i, false);
            session.getBasicRemote().sendText("|finalFrame" + i, true);
        }
        session.close();

        QueuedTextStreamer queuedTextStreamer = serverEndpoints.poll(5, TimeUnit.SECONDS);
        assertNotNull(queuedTextStreamer);
        for (int i = 0; i < numLoops; i++)
        {
            String msg = queuedTextStreamer.messages.poll(5, TimeUnit.SECONDS);
            String expected = "firstFrame" + i + "|secondFrame" + i + "|finalFrame" + i;
            assertThat(msg, Matchers.is(expected));
        }
    }

    @Test
    public void testMessageOrderingDoNotReadToEOF() throws Exception
    {
        ClientTextStreamer clientEndpoint = new ClientTextStreamer();
        Session session = wsClient.connectToServer(clientEndpoint, clientConfig, server.getWsUri().resolve("/partial"));
        QueuedTextStreamer serverEndpoint = Objects.requireNonNull(serverEndpoints.poll(5, TimeUnit.SECONDS));

        int serverInputBufferSize = 1024;
        JakartaWebSocketSession serverSession = (JakartaWebSocketSession)serverEndpoint.session;
        serverSession.getCoreSession().setInputBufferSize(serverInputBufferSize);

        // Write some initial data.
        Writer writer = session.getBasicRemote().getSendWriter();
        writer.write("first frame");
        writer.flush();

        // Signal to stop reading.
        writer.write("|");
        writer.flush();

        // Lots of data after we have stopped reading and onMessage exits.
        final String largePayload = StringUtil.stringFrom("x", serverInputBufferSize * 2);
        writer.write(largePayload);
        writer.close();

        session.close();
        assertTrue(clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(serverEndpoint.closeLatch.await(5, TimeUnit.SECONDS));
        assertNull(clientEndpoint.error.get());
        assertNull(serverEndpoint.error.get());

        String msg = serverEndpoint.messages.poll(5, TimeUnit.SECONDS);
        assertThat(msg, Matchers.is("first frame"));
    }

    public static class ClientTextStreamer extends WSEndpointTracker implements MessageHandler.Whole<Reader>
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final StringBuilder output = new StringBuilder();

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
            super.onOpen(session, config);
        }

        @Override
        public void onMessage(Reader input)
        {
            try
            {
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                    output.append((char)read);
                }
                latch.countDown();
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    @ServerEndpoint("/echo")
    public static class ServerTextStreamer
    {
        @OnMessage
        public void echo(Session session, Reader input) throws IOException
        {
            char[] buffer = new char[128];
            try (Writer output = session.getBasicRemote().getSendWriter())
            {
                long totalRead = 0;
                int read;
                while ((read = input.read(buffer)) >= 0)
                {
                    totalRead += read;
                    output.write(buffer, 0, read);
                }

                LOG.debug("{} total bytes read/write", totalRead);
            }
        }
    }

    public static class QueuedTextStreamer extends WSEndpointTracker implements MessageHandler.Whole<Reader>
    {
        protected BlockingArrayQueue<String> messages = new BlockingArrayQueue<>();

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(this);
            super.onOpen(session, config);
            serverEndpoints.add(this);
        }

        @Override
        public void onMessage(Reader input)
        {
            try
            {
                Thread.sleep(Math.abs(new Random().nextLong() % 200));
                messages.add(IO.toString(input));
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    public static class QueuedPartialTextStreamer extends QueuedTextStreamer
    {
        @Override
        public void onMessage(Reader input)
        {
            try
            {
                Thread.sleep(Math.abs(new Random().nextLong() % 200));

                // Do not read to EOF but just the first '|'.
                StringWriter writer = new StringWriter();
                while (true)
                {
                    int read = input.read();
                    if (read < 0 || read == '|')
                        break;
                    writer.write(read);
                }

                messages.add(writer.toString());
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }
}
