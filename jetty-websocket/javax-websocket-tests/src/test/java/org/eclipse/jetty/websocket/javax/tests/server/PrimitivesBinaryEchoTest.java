//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.javax.tests.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.javax.tests.Fuzzer;
import org.eclipse.jetty.websocket.javax.tests.LocalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test various {@link javax.websocket.Decoder.Binary Decoder.Binary} / {@link javax.websocket.Encoder.Binary Encoder.Binary} echo behavior of Java Primitives
 */
public class PrimitivesBinaryEchoTest
{
    private static final Logger LOG = Log.getLogger(PrimitivesBinaryEchoTest.class);

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
        data.add(Arguments.of(endpointClass.getSimpleName(), endpointClass, sendHex, expectHex));
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

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.start();
        server.getServerContainer().addEndpoint(ByteBufferEchoSocket.class);
        server.getServerContainer().addEndpoint(ByteArrayEchoSocket.class);
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("data")
    public void testPrimitiveEcho(String endpointClassname, Class<?> endpointClass, String sendHex, String expectHex) throws Exception
    {
        String requestPath = endpointClass.getAnnotation(ServerEndpoint.class).value();

        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.BINARY).setPayload(Hex.asByteBuffer(sendHex)));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        List<Frame> expect = new ArrayList<>();
        expect.add(new Frame(OpCode.BINARY).setPayload(Hex.asByteBuffer(expectHex)));
        expect.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        try (Fuzzer session = server.newNetworkFuzzer(requestPath))
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
}
