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
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.LocalServer;
import org.eclipse.jetty.websocket.core.CloseStatus;
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
 * Test various {@link jakarta.websocket.Decoder.Text Decoder.Text} / {@link jakarta.websocket.Encoder.Text Encoder.Text} echo behavior of Java Primitives
 */
public class PrimitivesTextEchoTest
{
    private static final Logger LOG = LoggerFactory.getLogger(PrimitivesTextEchoTest.class);

    public static class BaseSocket
    {
        @OnError
        public void onError(Throwable cause) throws IOException
        {
            LOG.warn("Error", cause);
        }
    }

    @ServerEndpoint("/echo/boolean")
    public static class BooleanEchoSocket extends BaseSocket
    {
        @OnMessage
        public boolean onMessage(boolean b) throws IOException
        {
            return b;
        }
    }

    @ServerEndpoint("/echo/boolean-obj")
    public static class BooleanObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Boolean onMessage(Boolean b) throws IOException
        {
            return b;
        }
    }

    @ServerEndpoint("/echo/byte")
    public static class ByteEchoSocket extends BaseSocket
    {
        @OnMessage
        public byte onMessage(byte b) throws IOException
        {
            return b;
        }
    }

    @ServerEndpoint("/echo/byte-obj")
    public static class ByteObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public byte onMessage(byte b) throws IOException
        {
            return b;
        }
    }

    @ServerEndpoint("/echo/char")
    public static class CharacterEchoSocket extends BaseSocket
    {
        @OnMessage
        public char onMessage(char c) throws IOException
        {
            return c;
        }
    }

    @ServerEndpoint("/echo/char-obj")
    public static class CharacterObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Character onMessage(Character c) throws IOException
        {
            return c;
        }
    }

    @ServerEndpoint("/echo/double")
    public static class DoubleEchoSocket extends BaseSocket
    {
        @OnMessage
        public double onMessage(double d) throws IOException
        {
            return d;
        }
    }

    @ServerEndpoint("/echo/double-obj")
    public static class DoubleObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Double onMessage(Double d) throws IOException
        {
            return d;
        }
    }

    @ServerEndpoint("/echo/float")
    public static class FloatEchoSocket extends BaseSocket
    {
        @OnMessage
        public float onMessage(float f) throws IOException
        {
            return f;
        }
    }

    @ServerEndpoint("/echo/float-obj")
    public static class FloatObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Float onMessage(Float f) throws IOException
        {
            return f;
        }
    }

    @ServerEndpoint("/echo/short")
    public static class ShortEchoSocket extends BaseSocket
    {
        @OnMessage
        public short onMessage(short s) throws IOException
        {
            return s;
        }
    }

    @ServerEndpoint("/echo/short-obj")
    public static class ShortObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Short onMessage(Short s) throws IOException
        {
            return s;
        }
    }

    @ServerEndpoint("/echo/integer")
    public static class IntegerEchoSocket extends BaseSocket
    {
        @OnMessage
        public int onMessage(int i) throws IOException
        {
            return i;
        }
    }

    @ServerEndpoint("/echo/integer-obj")
    public static class IntegerObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Integer onMessage(Integer i) throws IOException
        {
            return i;
        }
    }

    @ServerEndpoint("/echo/long")
    public static class LongEchoSocket extends BaseSocket
    {
        @OnMessage
        public long onMessage(long l) throws IOException
        {
            return l;
        }
    }

    @ServerEndpoint("/echo/long-obj")
    public static class LongObjEchoSocket extends BaseSocket
    {
        @OnMessage
        public Long onMessage(Long l) throws IOException
        {
            return l;
        }
    }

    @ServerEndpoint("/echo/string")
    public static class StringEchoSocket extends BaseSocket
    {
        @OnMessage
        public String onMessage(String s) throws IOException
        {
            return s;
        }
    }

    private static void addCase(List<Arguments> data, Class<?> endpointClass, String sendText, String expectText)
    {
        data.add(Arguments.of(endpointClass, sendText, expectText));
    }

    public static Stream<Arguments> data()
    {
        List<Arguments> data = new ArrayList<>();

        addCase(data, BooleanEchoSocket.class, "true", "true");
        addCase(data, BooleanEchoSocket.class, "TRUE", "true");
        addCase(data, BooleanEchoSocket.class, "false", "false");
        addCase(data, BooleanEchoSocket.class, "FALSE", "false");
        addCase(data, BooleanEchoSocket.class, "TRue", "true");
        addCase(data, BooleanEchoSocket.class, Boolean.toString(Boolean.TRUE), "true");
        addCase(data, BooleanEchoSocket.class, Boolean.toString(Boolean.FALSE), "false");
        addCase(data, BooleanEchoSocket.class, "Apple", "false");

        addCase(data, BooleanObjEchoSocket.class, "true", "true");
        addCase(data, BooleanObjEchoSocket.class, "TRUE", "true");
        addCase(data, BooleanObjEchoSocket.class, "false", "false");
        addCase(data, BooleanObjEchoSocket.class, "FALSE", "false");
        addCase(data, BooleanObjEchoSocket.class, "TRue", "true");
        addCase(data, BooleanObjEchoSocket.class, Boolean.toString(Boolean.TRUE), "true");
        addCase(data, BooleanObjEchoSocket.class, Boolean.toString(Boolean.FALSE), "false");
        addCase(data, BooleanObjEchoSocket.class, "Apple", "false");

        addCase(data, ByteEchoSocket.class, Byte.toString((byte)0x00), "0");
        addCase(data, ByteEchoSocket.class, Byte.toString((byte)0x58), "88");
        addCase(data, ByteEchoSocket.class, Byte.toString((byte)0x65), "101");
        addCase(data, ByteEchoSocket.class, Byte.toString(Byte.MAX_VALUE), "127");
        addCase(data, ByteEchoSocket.class, Byte.toString(Byte.MIN_VALUE), "-128");

        addCase(data, ByteObjEchoSocket.class, Byte.toString((byte)0x00), "0");
        addCase(data, ByteObjEchoSocket.class, Byte.toString((byte)0x58), "88");
        addCase(data, ByteObjEchoSocket.class, Byte.toString((byte)0x65), "101");
        addCase(data, ByteObjEchoSocket.class, Byte.toString(Byte.MAX_VALUE), "127");
        addCase(data, ByteObjEchoSocket.class, Byte.toString(Byte.MIN_VALUE), "-128");

        addCase(data, CharacterEchoSocket.class, Character.toString((char)40), "(");
        addCase(data, CharacterEchoSocket.class, Character.toString((char)106), "j");
        addCase(data, CharacterEchoSocket.class, Character.toString((char)64), "@");
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        addCase(data, CharacterEchoSocket.class, Character.toString((char)0x262f), "\u262f");

        addCase(data, CharacterObjEchoSocket.class, Character.toString((char)40), "(");
        addCase(data, CharacterObjEchoSocket.class, Character.toString((char)106), "j");
        addCase(data, CharacterObjEchoSocket.class, Character.toString((char)64), "@");
        addCase(data, CharacterObjEchoSocket.class, Character.toString((char)0x262f), "\u262f");

        addCase(data, DoubleEchoSocket.class, Double.toString(3.1459), "3.1459");
        addCase(data, DoubleEchoSocket.class, Double.toString(123.456), "123.456");
        addCase(data, DoubleEchoSocket.class, Double.toString(55), "55.0");
        addCase(data, DoubleEchoSocket.class, Double.toString(.123), "0.123");
        addCase(data, DoubleEchoSocket.class, Double.toString(Double.MAX_VALUE), Double.toString(Double.MAX_VALUE));
        addCase(data, DoubleEchoSocket.class, Double.toString(Double.MIN_VALUE), Double.toString(Double.MIN_VALUE));

        addCase(data, DoubleObjEchoSocket.class, Double.toString(3.1459), "3.1459");
        addCase(data, DoubleObjEchoSocket.class, Double.toString(123.456), "123.456");
        addCase(data, DoubleObjEchoSocket.class, Double.toString(55), "55.0");
        addCase(data, DoubleObjEchoSocket.class, Double.toString(.123), "0.123");
        addCase(data, DoubleObjEchoSocket.class, Double.toString(Double.MAX_VALUE), Double.toString(Double.MAX_VALUE));
        addCase(data, DoubleObjEchoSocket.class, Double.toString(Double.MIN_VALUE), Double.toString(Double.MIN_VALUE));

        addCase(data, FloatEchoSocket.class, Float.toString(3.1459f), "3.1459");
        addCase(data, FloatEchoSocket.class, Float.toString(0.0f), "0.0");
        addCase(data, FloatEchoSocket.class, Float.toString(Float.MAX_VALUE), Float.toString(Float.MAX_VALUE));
        addCase(data, FloatEchoSocket.class, Float.toString(Float.MIN_VALUE), Float.toString(Float.MIN_VALUE));

        addCase(data, FloatObjEchoSocket.class, Float.toString(3.1459f), "3.1459");
        addCase(data, FloatObjEchoSocket.class, Float.toString(0.0f), "0.0");
        addCase(data, FloatObjEchoSocket.class, Float.toString(Float.MAX_VALUE), Float.toString(Float.MAX_VALUE));
        addCase(data, FloatObjEchoSocket.class, Float.toString(Float.MIN_VALUE), Float.toString(Float.MIN_VALUE));

        addCase(data, ShortEchoSocket.class, Short.toString((short)0), "0");
        addCase(data, ShortEchoSocket.class, Short.toString((short)30000), "30000");
        addCase(data, ShortEchoSocket.class, Short.toString(Short.MAX_VALUE), Short.toString(Short.MAX_VALUE));
        addCase(data, ShortEchoSocket.class, Short.toString(Short.MIN_VALUE), Short.toString(Short.MIN_VALUE));

        addCase(data, ShortObjEchoSocket.class, Short.toString((short)0), "0");
        addCase(data, ShortObjEchoSocket.class, Short.toString((short)30000), "30000");
        addCase(data, ShortObjEchoSocket.class, Short.toString(Short.MAX_VALUE), Short.toString(Short.MAX_VALUE));
        addCase(data, ShortObjEchoSocket.class, Short.toString(Short.MIN_VALUE), Short.toString(Short.MIN_VALUE));

        addCase(data, IntegerEchoSocket.class, Integer.toString(0), "0");
        addCase(data, IntegerEchoSocket.class, Integer.toString(100_000), "100000");
        addCase(data, IntegerEchoSocket.class, Integer.toString(-2_000_000), "-2000000");
        addCase(data, IntegerEchoSocket.class, Integer.toString(Integer.MAX_VALUE), Integer.toString(Integer.MAX_VALUE));
        addCase(data, IntegerEchoSocket.class, Integer.toString(Integer.MIN_VALUE), Integer.toString(Integer.MIN_VALUE));

        addCase(data, IntegerObjEchoSocket.class, Integer.toString(0), "0");
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(100_000), "100000");
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(-2_000_000), "-2000000");
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(Integer.MAX_VALUE), Integer.toString(Integer.MAX_VALUE));
        addCase(data, IntegerObjEchoSocket.class, Integer.toString(Integer.MIN_VALUE), Integer.toString(Integer.MIN_VALUE));

        addCase(data, LongEchoSocket.class, Long.toString(0), "0");
        addCase(data, LongEchoSocket.class, Long.toString(100_000), "100000");
        addCase(data, LongEchoSocket.class, Long.toString(-2_000_000), "-2000000");
        addCase(data, LongEchoSocket.class, Long.toString(300_000_000_000L), "300000000000");
        addCase(data, LongEchoSocket.class, Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE));
        addCase(data, LongEchoSocket.class, Long.toString(Long.MIN_VALUE), Long.toString(Long.MIN_VALUE));

        addCase(data, LongObjEchoSocket.class, Long.toString(0), "0");
        addCase(data, LongObjEchoSocket.class, Long.toString(100_000), "100000");
        addCase(data, LongObjEchoSocket.class, Long.toString(-2_000_000), "-2000000");
        addCase(data, LongObjEchoSocket.class, Long.toString(300_000_000_000L), "300000000000");
        addCase(data, LongObjEchoSocket.class, Long.toString(Long.MAX_VALUE), Long.toString(Long.MAX_VALUE));
        addCase(data, LongObjEchoSocket.class, Long.toString(Long.MIN_VALUE), Long.toString(Long.MIN_VALUE));

        addCase(data, StringEchoSocket.class, "Hello World", "Hello World");
        return data.stream();
    }

    private static LocalServer server;
    private static JakartaWebSocketClientContainer client;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        ServerContainer container = server.getServerContainer();
        container.addEndpoint(BooleanEchoSocket.class);
        container.addEndpoint(BooleanObjEchoSocket.class);
        container.addEndpoint(ByteEchoSocket.class);
        container.addEndpoint(ByteObjEchoSocket.class);
        container.addEndpoint(CharacterEchoSocket.class);
        container.addEndpoint(CharacterObjEchoSocket.class);
        container.addEndpoint(DoubleEchoSocket.class);
        container.addEndpoint(DoubleObjEchoSocket.class);
        container.addEndpoint(FloatEchoSocket.class);
        container.addEndpoint(FloatObjEchoSocket.class);
        container.addEndpoint(ShortEchoSocket.class);
        container.addEndpoint(ShortObjEchoSocket.class);
        container.addEndpoint(IntegerEchoSocket.class);
        container.addEndpoint(IntegerObjEchoSocket.class);
        container.addEndpoint(LongEchoSocket.class);
        container.addEndpoint(LongObjEchoSocket.class);
        container.addEndpoint(StringEchoSocket.class);

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
    public void testPrimitiveEcho(Class<?> endpointClass, String sendText, String expectText) throws Exception
    {
        String requestPath = endpointClass.getAnnotation(ServerEndpoint.class).value();
        EventSocket clientSocket = new EventSocket();
        URI uri = server.getWsUri().resolve(requestPath);
        try (Session session = client.connectToServer(clientSocket, uri))
        {
            session.getBasicRemote().sendText(sendText);
            assertThat(clientSocket.textMessages.poll(5, TimeUnit.SECONDS), is(expectText));
        }

        assertTrue(clientSocket.closeLatch.await(5, TimeUnit.SECONDS));
        assertThat(clientSocket.closeReason.getCloseCode().getCode(), is(CloseStatus.NORMAL));
    }
}
