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

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.websocket.jakarta.tests.LocalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test various {@link jakarta.websocket.Decoder.Binary Decoder.Binary} / {@link jakarta.websocket.Encoder.Binary Encoder.Binary} echo behavior of Java Primitives
 */
public class PrimitivesBinaryEchoTest
{
    private static final Logger LOG = LoggerFactory.getLogger(PrimitivesBinaryEchoTest.class);

    public static class BaseSocket
    {
        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }

    @ServerEndpoint("/echo/bytebuffer")
    public static class ByteBufferEchoSocket extends BaseSocket
    {
        @OnMessage
        public ByteBuffer onMessage(ByteBuffer buf) throws IOException
        {
            ByteBuffer ret = ByteBuffer.allocate(buf.remaining() + 1);
            ret.put((byte)0xFF); // proof that this endpoint got it
            ret.put(buf);
            ret.flip();
            return ret;
        }
    }

    @ServerEndpoint("/echo/bytearray")
    public static class ByteArrayEchoSocket extends BaseSocket
    {
        @OnMessage
        public byte[] onMessage(byte[] buf) throws IOException
        {
            byte[] ret = new byte[buf.length + 1];
            ret[0] = (byte)0xFE; // proof that this endpoint got it
            System.arraycopy(buf, 0, ret, 1, buf.length);
            return ret;
        }
    }

    private static void addCase(List<Arguments> data, Class<?> endpointClass, String sendHex, String expectHex)
    {
        data.add(Arguments.of(endpointClass, sendHex, expectHex));
    }

    public static Stream<Arguments> data()
    {
        List<Arguments> data = new ArrayList<>();

        addCase(data, ByteBufferEchoSocket.class, "00", "FF00");
        addCase(data, ByteBufferEchoSocket.class, "001133445566778899AA", "FF001133445566778899AA");
        addCase(data, ByteBufferEchoSocket.class, "11112222333344445555", "FF11112222333344445555");

        addCase(data, ByteArrayEchoSocket.class, "00", "FE00");
        addCase(data, ByteArrayEchoSocket.class, "001133445566778899AA", "FE001133445566778899AA");
        addCase(data, ByteArrayEchoSocket.class, "11112222333344445555", "FE11112222333344445555");

        return data.stream();
    }

    private static LocalServer server;
    private static JakartaWebSocketClientContainer client;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(ByteBufferEchoSocket.class);
        server.getServerContainer().addEndpoint(ByteArrayEchoSocket.class);

        client = new JakartaWebSocketClientContainer();
        client.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        client.stop();
        server.stop();
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("data")
    public void testPrimitiveEcho(Class<?> endpointClass, String sendHex, String expectHex) throws Exception
    {
        String requestPath = endpointClass.getAnnotation(ServerEndpoint.class).value();
        EventSocket clientSocket = new EventSocket();
        URI uri = server.getWsUri().resolve(requestPath);
        try (Session session = client.connectToServer(clientSocket, uri))
        {
            session.getBasicRemote().sendBinary(Hex.asByteBuffer(sendHex));
            assertThat(clientSocket.binaryMessages.poll(5, TimeUnit.SECONDS), is(Hex.asByteBuffer(expectHex)));
        }

        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeReason.getCloseCode().getCode(), is(CloseStatus.NORMAL));
    }
}
