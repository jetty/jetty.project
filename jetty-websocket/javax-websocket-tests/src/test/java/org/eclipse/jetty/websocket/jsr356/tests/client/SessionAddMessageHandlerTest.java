//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests.client;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.DummyCoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.jsr356.ConfiguredEndpoint;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandler;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketFrameHandlerFactory;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.UpgradeRequest;
import org.eclipse.jetty.websocket.jsr356.UpgradeRequestAdapter;
import org.eclipse.jetty.websocket.jsr356.UpgradeResponse;
import org.eclipse.jetty.websocket.jsr356.UpgradeResponseAdapter;
import org.eclipse.jetty.websocket.jsr356.client.EmptyClientEndpointConfig;
import org.eclipse.jetty.websocket.jsr356.client.JavaxWebSocketClientContainer;
import org.eclipse.jetty.websocket.jsr356.client.JavaxWebSocketClientFrameHandlerFactory;
import org.eclipse.jetty.websocket.jsr356.tests.MessageType;
import org.eclipse.jetty.websocket.jsr356.tests.handlers.ByteArrayWholeHandler;
import org.eclipse.jetty.websocket.jsr356.tests.handlers.ByteBufferPartialHandler;
import org.eclipse.jetty.websocket.jsr356.tests.handlers.LongMessageHandler;
import org.eclipse.jetty.websocket.jsr356.tests.handlers.StringWholeHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.eclipse.jetty.websocket.jsr356.tests.SessionMatchers.isMessageHandlerType;
import static org.eclipse.jetty.websocket.jsr356.tests.SessionMatchers.isMessageHandlerTypeRegistered;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

public class SessionAddMessageHandlerTest
{
    @ClientEndpoint
    public static class DummyEndpoint
    {
    }

    private JavaxWebSocketClientContainer container;
    private JavaxWebSocketFrameHandler frameHandler;
    private JavaxWebSocketSession session;

    @BeforeEach
    public void initSession() throws Exception
    {
        // Container
        container = new JavaxWebSocketClientContainer();
        container.start();
        ClientEndpointConfig endpointConfig = new EmptyClientEndpointConfig();
        ConfiguredEndpoint ei = new ConfiguredEndpoint(new DummyEndpoint(), endpointConfig);

        UpgradeRequest handshakeRequest = new UpgradeRequestAdapter();
        UpgradeResponse handshakeResponse = new UpgradeResponseAdapter();

        JavaxWebSocketFrameHandlerFactory frameHandlerFactory = new JavaxWebSocketClientFrameHandlerFactory(container);
        CompletableFuture<Session> futureSession = new CompletableFuture<>();
        frameHandler = frameHandlerFactory.newJavaxFrameHandler(ei, handshakeRequest, handshakeResponse, futureSession);
        frameHandler.onOpen(new DummyCoreSession());

        // Session
        session = frameHandler.getSession();
        session.start();
    }

    @AfterEach
    public void stopSession() throws Exception
    {
        session.stop();
        container.stop();
    }

    @Test
    public void testMessageHandlerBinary()
    {
        session.addMessageHandler(new ByteBufferPartialHandler());
        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.BINARY));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.TEXT)));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.PONG)));

        assertThat("session.messageHandlers", session.getMessageHandlers(),
            hasItem(
                allOf(
                    isMessageHandlerType(session, MessageType.BINARY),
                    instanceOf(ByteBufferPartialHandler.class)
                )
            )
        );
    }

    @Test
    public void testMessageHandlerBoth()
    {
        session.addMessageHandler(new StringWholeHandler());
        session.addMessageHandler(new ByteArrayWholeHandler());
        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.BINARY));
        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.TEXT));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.PONG)));

        assertThat("session.messageHandlers", session.getMessageHandlers(),
            hasItem(
                allOf(
                    isMessageHandlerType(session, MessageType.BINARY),
                    instanceOf(ByteArrayWholeHandler.class)
                )
            )
        );

        assertThat("session.messageHandlers", session.getMessageHandlers(),
            hasItem(
                allOf(
                    isMessageHandlerType(session, MessageType.TEXT),
                    instanceOf(StringWholeHandler.class)
                )
            )
        );
    }

    @Test
    public void testMessageHandlerReplaceTextHandler()
    {
        MessageHandler strHandler = new StringWholeHandler();
        session.addMessageHandler(strHandler); // add a TEXT handler
        session.addMessageHandler(new ByteArrayWholeHandler()); // add BINARY handler
        session.removeMessageHandler(strHandler); // remove original TEXT handler
        session.addMessageHandler(new LongMessageHandler()); // add new TEXT handler

        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.BINARY));
        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.TEXT));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.PONG)));

        // Final expected BINARY implementation
        assertThat("session.messageHandlers", session.getMessageHandlers(),
            hasItem(
                allOf(
                    isMessageHandlerType(session, MessageType.BINARY),
                    instanceOf(ByteArrayWholeHandler.class)
                )
            )
        );

        // Final expected TEXT implementation
        assertThat("session.messageHandlers", session.getMessageHandlers(),
            hasItem(
                allOf(
                    isMessageHandlerType(session, MessageType.TEXT),
                    instanceOf(LongMessageHandler.class)
                )
            )
        );
    }

    @Test
    public void testMessageHandlerAddRemoveLambda()
    {
        final List<String> received = new ArrayList<>();
        MessageHandler.Whole<String> lamdaHandler = (msg) -> received.add(msg);

        session.addMessageHandler(String.class, lamdaHandler); // add a TEXT handler lambda
        session.addMessageHandler(new ByteArrayWholeHandler()); // add BINARY handler
        session.removeMessageHandler(lamdaHandler); // remove original TEXT handler

        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.BINARY));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.TEXT)));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.PONG)));

        // Final expected BINARY implementation
        assertThat("session.messageHandlers", session.getMessageHandlers(),
            hasItem(
                allOf(
                    isMessageHandlerType(session, MessageType.BINARY),
                    instanceOf(ByteArrayWholeHandler.class)
                )
            )
        );
    }

    @Test
    public void testMessageHandlerText()
    {
        session.addMessageHandler(new StringWholeHandler());

        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.BINARY)));
        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.TEXT));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.PONG)));

        // Final expected TEXT implementation
        assertThat("session.messageHandlers", session.getMessageHandlers(),
            hasItem(
                allOf(
                    isMessageHandlerType(session, MessageType.TEXT),
                    instanceOf(StringWholeHandler.class)
                )
            )
        );
    }

    /**
     * Test Java 8 Lamba of {@link javax.websocket.MessageHandler.Whole}
     */
    @Test
    public void testMessageHandler_11_WholeLambda() throws Exception
    {
        final List<String> received = new ArrayList<>();

        // Whole Message
        session.addMessageHandler(String.class, (msg) -> received.add(msg));

        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.BINARY)));
        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.TEXT));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.PONG)));

        frameHandler.onFrame(new Frame(OpCode.TEXT).setPayload("G'day").setFin(true), Callback.NOOP);
        frameHandler.onFrame(new Frame(OpCode.TEXT).setPayload("Hello World").setFin(true), Callback.NOOP);

        assertThat("Received msgs", received.size(), is(2));
        assertThat("Received Message[0]", received.get(0), is("G'day"));
        assertThat("Received Message[1]", received.get(1), is("Hello World"));

        session.close();
    }

    /**
     * Test Java 8 Lamba of {@link javax.websocket.MessageHandler.Partial}
     */
    @Test
    public void testMessageHandler_11_PartialLambda() throws Exception
    {
        final List<Object[]> received = new ArrayList<>();

        // Partial Message
        session.addMessageHandler(ByteBuffer.class, (partialMsg, isLast) ->
        {
            ByteBuffer copy = ByteBuffer.allocate(partialMsg.remaining());
            copy.put(partialMsg);
            copy.flip();
            received.add(new Object[] { copy, isLast });
        });

        assertThat("session", session, isMessageHandlerTypeRegistered(MessageType.BINARY));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.TEXT)));
        assertThat("session", session, not(isMessageHandlerTypeRegistered(MessageType.PONG)));

        frameHandler.onFrame(new Frame(OpCode.BINARY).setPayload("G'day").setFin(false), Callback.NOOP);
        frameHandler.onFrame(new Frame(OpCode.CONTINUATION).setPayload(" World").setFin(true), Callback.NOOP);

        assertThat("Received partial", received.size(), is(2));
        assertThat("Received Message[0].buffer", BufferUtil.toUTF8String((ByteBuffer)received.get(0)[0]), is("G'day"));
        assertThat("Received Message[0].last", received.get(0)[1], is(false));
        assertThat("Received Message[1].buffer", BufferUtil.toUTF8String((ByteBuffer)received.get(1)[0]), is(" World"));
        assertThat("Received Message[1].last", received.get(1)[1], is(true));
    }
}
