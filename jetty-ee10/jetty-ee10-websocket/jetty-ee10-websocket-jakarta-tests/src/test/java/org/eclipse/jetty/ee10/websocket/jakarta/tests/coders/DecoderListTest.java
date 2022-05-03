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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.coders;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.websocket.Decoder;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.websocket.jakarta.client.internal.JakartaWebSocketClientContainer;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.AbstractDecoder;
import org.eclipse.jetty.ee10.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.EventSocket;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.WSURI;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class DecoderListTest
{
    private Server server;
    private ServletContextHandler contextHandler;
    private URI serverUri;
    private JakartaWebSocketClientContainer client;

    @BeforeEach
    public void before() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/");
        server.setHandler(contextHandler);

        client = new JakartaWebSocketClientContainer();
        client.start();
    }

    @AfterEach
    public void after() throws Exception
    {
        server.stop();
        client.stop();
    }

    public interface CheckedConsumer<T>
    {
        void accept(T t) throws DeploymentException;
    }

    public void start(CheckedConsumer<ServerContainer> containerConsumer) throws Exception
    {
        JakartaWebSocketServletContainerInitializer.configure(contextHandler, (context, container) ->
            containerConsumer.accept(container));
        server.start();
        serverUri = WSURI.toWebsocket(server.getURI());
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
        start(container ->
        {
            ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder.create(TextDecoderListEndpoint.class, "/")
                .decoders(List.of(EqualsTextDecoder.class, PlusTextDecoder.class, MinusTextDecoder.class))
                .build();
            container.addEndpoint(endpointConfig);
        });

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
        start(container ->
        {
            ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder.create(BinaryDecoderListEndpoint.class, "/")
                .decoders(List.of(EqualsBinaryDecoder.class, PlusBinaryDecoder.class, MinusBinaryDecoder.class))
                .build();
            container.addEndpoint(endpointConfig);
        });

        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendBinary(BufferUtil.toBuffer(request));
        ByteBuffer response = clientEndpoint.binaryMessages.poll(3, TimeUnit.SECONDS);
        assertThat(BufferUtil.toString(response), is(expected));
    }

    @Test
    public void testDecoderOrder() throws Exception
    {
        start(container ->
        {
            ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder.create(TextDecoderListEndpoint.class, "/")
                .decoders(List.of(AppendingPlusDecoder.class, AppendingMinusDecoder.class))
                .build();
            container.addEndpoint(endpointConfig);
        });

        // The AppendingPlusDecoder should be the one used as it was first in the list.
        EventSocket clientEndpoint = new EventSocket();
        Session session = client.connectToServer(clientEndpoint, serverUri);
        session.getBasicRemote().sendText("");
        String response = clientEndpoint.textMessages.poll(3, TimeUnit.SECONDS);
        assertThat(response, is("+"));
    }

    @Test
    public void testStreamDecoders()
    {
        // Stream decoders will not be able to form a decoder list as they don't implement willDecode().
        Throwable error = assertThrows(Throwable.class, () ->
            start(container ->
            {
                ServerEndpointConfig endpointConfig = ServerEndpointConfig.Builder.create(TextDecoderListEndpoint.class, "/")
                    .decoders(List.of(TextStreamDecoder1.class, TextStreamDecoder2.class))
                    .build();
                container.addEndpoint(endpointConfig);
            })
        );

        assertThat(error, instanceOf(RuntimeException.class));
        Throwable cause = error.getCause();
        assertThat(cause, instanceOf(DeploymentException.class));
        Throwable invalidWebSocketException = cause.getCause();
        assertThat(invalidWebSocketException, instanceOf(InvalidWebSocketException.class));
        assertThat(invalidWebSocketException.getMessage(), containsString("Multiple decoders for objectTypeclass java.lang.String"));
    }

    public static class TextDecoderListEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(new PartialTextHandler(session));
        }
    }

    public static class BinaryDecoderListEndpoint extends Endpoint
    {
        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.addMessageHandler(new PartialBinaryHandler(session));
        }

        @Override
        public void onError(Session session, Throwable t)
        {
            t.printStackTrace();
        }
    }

    private static class PartialTextHandler implements MessageHandler.Whole<String>
    {
        private final Session _session;

        public PartialTextHandler(Session session)
        {
            _session = session;
        }

        @Override
        public void onMessage(String message)
        {
            _session.getAsyncRemote().sendText(message);
        }
    }

    private static class PartialBinaryHandler implements MessageHandler.Whole<ByteBufferWrapper>
    {
        private final Session _session;

        public PartialBinaryHandler(Session session)
        {
            _session = session;
        }

        @Override
        public void onMessage(ByteBufferWrapper message)
        {
            _session.getAsyncRemote().sendBinary(message.getByteBuffer());
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

    public static class AppendingPlusDecoder extends AppendingStringDecoder
    {
        public AppendingPlusDecoder()
        {
            super("+");
        }
    }

    public static class AppendingMinusDecoder extends AppendingStringDecoder
    {
        public AppendingMinusDecoder()
        {
            super("-");
        }
    }

    public static class TextStreamDecoder1 extends AbstractDecoder implements Decoder.TextStream<String>
    {
        @Override
        public String decode(Reader reader) throws IOException
        {
            return "Decoder1: " + IO.toString(reader);
        }
    }

    public static class TextStreamDecoder2 extends AbstractDecoder implements Decoder.TextStream<String>
    {
        @Override
        public String decode(Reader reader) throws IOException
        {
            return "Decoder2: " + IO.toString(reader);
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

    public static class AppendingStringDecoder extends AbstractDecoder implements Decoder.Text<String>
    {
        private final String s;

        public AppendingStringDecoder(String prefix)
        {
            this.s = prefix;
        }

        @Override
        public String decode(String message)
        {
            return message + s;
        }

        @Override
        public boolean willDecode(String message)
        {
            return true;
        }
    }
}
