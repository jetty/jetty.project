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

package org.eclipse.jetty.websocket.jakarta.tests.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.websocket.jakarta.tests.DataUtils;
import org.eclipse.jetty.websocket.jakarta.tests.Fuzzer;
import org.eclipse.jetty.websocket.jakarta.tests.LocalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BinaryStreamTest
{
    private static final String PATH = "/echo";

    private static LocalServer server;
    private static JakartaWebSocketClientContainer wsClient;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        ServerEndpointConfig config = ServerEndpointConfig.Builder.create(ServerBinaryStreamer.class, PATH).build();
        server.getServerContainer().addEndpoint(config);

        wsClient = new JakartaWebSocketClientContainer();
        wsClient.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        wsClient.stop();
        server.stop();
    }

    @Test
    public void testEchoWithMediumMessage() throws Exception
    {
        testEcho(1024);
    }

    @Test
    public void testLargestMessage() throws Exception
    {
        testEcho(server.getServerContainer().getDefaultMaxBinaryMessageBufferSize());
    }

    private byte[] newData(int size)
    {
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
        send.add(new Frame(OpCode.BINARY).setPayload(data));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        ByteBuffer expectedMessage = DataUtils.copyOf(data);

        try (Fuzzer session = server.newNetworkFuzzer("/echo"))
        {
            session.sendBulk(send);
            BlockingQueue<Frame> receivedFrames = session.getOutputFrames();
            session.expectMessage(receivedFrames, OpCode.BINARY, expectedMessage);
        }
    }

    @Test
    public void testMoreThanLargestMessageOneByteAtATime() throws Exception
    {
        int size = server.getServerContainer().getDefaultMaxBinaryMessageBufferSize() + 16;
        byte[] data = newData(size);

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.BINARY).setPayload(data));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        ByteBuffer expectedMessage = DataUtils.copyOf(data);

        try (Fuzzer session = server.newNetworkFuzzer("/echo"))
        {
            session.sendSegmented(send, 1);
            BlockingQueue<Frame> receivedFrames = session.getOutputFrames();
            session.expectMessage(receivedFrames, OpCode.BINARY, expectedMessage);
        }
    }

    @Test
    public void testNotReadingToEndOfStream() throws Exception
    {
        int size = 32;
        byte[] data = newData(size);
        URI uri = server.getWsUri().resolve(PATH);

        CountDownLatch handlerComplete = new CountDownLatch(1);
        BasicClientBinaryStreamer client = new BasicClientBinaryStreamer((session, inputStream) ->
        {
            byte[] recv = new byte[16];
            int read = inputStream.read(recv);
            assertThat(read, not(is(0)));
            handlerComplete.countDown();
        });

        Session session = wsClient.connectToServer(client, uri);
        session.getBasicRemote().sendBinary(BufferUtil.toBuffer(data));
        assertTrue(handlerComplete.await(5, TimeUnit.SECONDS));

        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "close from test"));
        assertTrue(client.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(client.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
        assertThat(client.closeReason.getReasonPhrase(), is("close from test"));
    }

    @Test
    public void testClosingBeforeReadingToEndOfStream() throws Exception
    {
        int size = 32;
        byte[] data = newData(size);
        URI uri = server.getWsUri().resolve(PATH);

        CountDownLatch handlerComplete = new CountDownLatch(1);
        BasicClientBinaryStreamer client = new BasicClientBinaryStreamer((session, inputStream) ->
        {
            byte[] recv = new byte[16];
            int read = inputStream.read(recv);
            assertThat(read, not(is(0)));

            inputStream.close();
            assertThrows(IOException.class, inputStream::read);
            handlerComplete.countDown();
        });

        Session session = wsClient.connectToServer(client, uri);
        session.getBasicRemote().sendBinary(BufferUtil.toBuffer(data));
        assertTrue(handlerComplete.await(5, TimeUnit.SECONDS));

        session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "close from test"));
        assertTrue(client.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(client.closeReason.getCloseCode(), is(CloseReason.CloseCodes.NORMAL_CLOSURE));
        assertThat(client.closeReason.getReasonPhrase(), is("close from test"));
    }

    @ClientEndpoint
    public static class BasicClientBinaryStreamer
    {
        public interface MessageHandler
        {
            void accept(Session session, InputStream inputStream) throws Exception;
        }

        private final MessageHandler handler;
        private final CountDownLatch closeLatch = new CountDownLatch(1);
        private CloseReason closeReason;

        public BasicClientBinaryStreamer(MessageHandler consumer)
        {
            this.handler = consumer;
        }

        @OnMessage
        public void echoed(Session session, InputStream input) throws Exception
        {
            handler.accept(session, input);
        }

        @OnClose
        public void onClosed(CloseReason closeReason)
        {
            this.closeReason = closeReason;
            closeLatch.countDown();
        }
    }

    @ClientEndpoint
    public static class ClientBinaryStreamer
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @OnMessage
        public void echoed(InputStream input) throws IOException
        {
            while (true)
            {
                int read = input.read();
                if (read < 0)
                    break;
                output.write(read);
            }
            latch.countDown();
        }

        public byte[] getEcho()
        {
            return output.toByteArray();
        }

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException
        {
            return latch.await(timeout, unit);
        }
    }

    @ServerEndpoint(PATH)
    public static class ServerBinaryStreamer
    {
        private static final Logger LOG = LoggerFactory.getLogger(ServerBinaryStreamer.class);

        @OnMessage
        public void echo(Session session, InputStream input) throws IOException
        {
            byte[] buffer = new byte[128];
            try (OutputStream output = session.getBasicRemote().getSendStream())
            {
                int readCount = 0;
                int read;
                while ((read = input.read(buffer)) >= 0)
                {
                    output.write(buffer, 0, read);
                    readCount += read;
                }
                LOG.debug("Read {} bytes", readCount);
            }
        }
    }
}
