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

package org.eclipse.jetty.websocket.javax.tests.coders;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.websocket.Decoder;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.javax.client.internal.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.javax.common.decoders.AbstractDecoder;
import org.eclipse.jetty.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.javax.tests.EventSocket;
import org.eclipse.jetty.websocket.javax.tests.WSURI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class DecoderListTest
{
    private Server server;
    private URI serverUri;
    private JavaxWebSocketClientContainer client;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);
        JavaxWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            container.addEndpoint(DecoderListEndpoint.class));
        server.start();
        serverUri = WSURI.toWebsocket(server.getURI());

        client = new JavaxWebSocketClientContainer();
        client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
        client.stop();
    }

    public static Stream<Arguments> getTextArguments()
    {
        return Stream.of(
            Arguments.of("=DecodeEquals", "DecodeEquals="),
            Arguments.of("+DecodePlus", "DecodePlus+"),
            Arguments.of("-DecodeMinus", "DecodeMinus-"),
            Arguments.of("DecodeNoMatch", "DecodeNoMatch")
        );
    }

    public static Stream<Arguments> getBinaryArguments()
    {
        return Stream.of(
            Arguments.of("=DecodeEquals", "DecodeEquals="),
            Arguments.of("+DecodePlus", "DecodePlus+"),
            Arguments.of("-DecodeMinus", "DecodeMinus-"),
            // No decoder accepts this message and we have no default decoder for this type, so we get no response.
            Arguments.of("DecodeNoMatch", null)
        );
    }

    @ParameterizedTest
    @MethodSource("getTextArguments")
    public void testTextDecoderList(String request, String expected) throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendText(request);
        String response = clientEndpoint.textMessages.poll(3, TimeUnit.SECONDS);
        assertThat(response, is(expected));
    }

    @ParameterizedTest
    @MethodSource("getBinaryArguments")
    public void testBinaryDecoderList(String request, String expected) throws Exception
    {
        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendBinary(BufferUtil.toBuffer(request));
        ByteBuffer response = clientEndpoint.binaryMessages.poll(3, TimeUnit.SECONDS);
        assertThat(BufferUtil.toString(response), is(expected));
    }

    @ServerEndpoint(value = "/", decoders = {EqualsTextDecoder.class, PlusTextDecoder.class, MinusTextDecoder.class,
                                             EqualsBinaryDecoder.class, PlusBinaryDecoder.class, MinusBinaryDecoder.class})
    public static class DecoderListEndpoint
    {
        @OnMessage
        public String echo(String message)
        {
            return message;
        }

        @OnMessage
        public ByteBuffer echo(ByteBufferWrapper message)
        {
            return message.getByteBuffer();
        }
    }

    public static class ByteBufferWrapper
    {
        private final ByteBuffer byteBuffer;

        public ByteBufferWrapper(ByteBuffer byteBuffer)
        {
            this.byteBuffer = byteBuffer;
        }

        public ByteBuffer getByteBuffer()
        {
            return byteBuffer;
        }
    }

    public static class EqualsBinaryDecoder extends ByteBufferWrapperDecoder
    {
        public EqualsBinaryDecoder()
        {
            super("=");
        }
    }

    public static class PlusBinaryDecoder extends ByteBufferWrapperDecoder
    {
        public PlusBinaryDecoder()
        {
            super("+");
        }
    }

    public static class MinusBinaryDecoder extends ByteBufferWrapperDecoder
    {
        public MinusBinaryDecoder()
        {
            super("-");
        }
    }

    public static class EqualsTextDecoder extends PrefixStringDecoder
    {
        public EqualsTextDecoder()
        {
            super("=");
        }
    }

    public static class PlusTextDecoder extends PrefixStringDecoder
    {
        public PlusTextDecoder()
        {
            super("+");
        }
    }

    public static class MinusTextDecoder extends PrefixStringDecoder
    {
        public MinusTextDecoder()
        {
            super("-");
        }
    }

    public static class ByteBufferWrapperDecoder extends AbstractDecoder implements Decoder.Binary<ByteBufferWrapper>
    {
        private final String prefix;

        public ByteBufferWrapperDecoder(String prefix)
        {
            this.prefix = prefix;
        }

        @Override
        public ByteBufferWrapper decode(ByteBuffer bytes)
        {
            String s = BufferUtil.toString(bytes).substring(prefix.length()) + prefix;
            return new ByteBufferWrapper(BufferUtil.toBuffer(s));
        }

        @Override
        public boolean willDecode(ByteBuffer bytes)
        {
            return BufferUtil.toString(bytes).startsWith(prefix);
        }
    }

    public static class PrefixStringDecoder extends AbstractDecoder implements Decoder.Text<String>
    {
        private final String prefix;

        public PrefixStringDecoder(String prefix)
        {
            this.prefix = prefix;
        }

        @Override
        public String decode(String s)
        {
            return s.substring(prefix.length()) + prefix;
        }

        @Override
        public boolean willDecode(String s)
        {
            return s.startsWith(prefix);
        }
    }
}
