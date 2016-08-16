//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.instanceOf;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

/**
 * This class tests receiving of messages by different types of {@link MessageHandler}
 */
public class MessageReceivingTest {
    private static final Logger LOG = Log.getLogger(EndpointEchoTest.class);
    private static Server server;
    private static EchoHandler handler;
    private static URI serverUri;
    private WebSocketContainer container;
    private final String VERY_LONG_STRING;

    public MessageReceivingTest() {
        byte raw[] = new byte[1024 * 1024];
        Arrays.fill(raw, (byte)'x');
        VERY_LONG_STRING = new String(raw, StandardCharsets.UTF_8);
    }

    @BeforeClass
    public static void startServer() throws Exception {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        handler = new EchoHandler();

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(handler);
        server.setHandler(context);

        // Start Server
        server.start();

        String host = connector.getHost();
        if (host == null) {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/", host, port));
    }

    @AfterClass
    public static void stopServer() {
        try {
            server.stop();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }

    @Before
    public void configureTest() {
        container = ContainerProvider.getWebSocketContainer();
    }

    /**
     * Method tests receiving of text messages at once.
     *
     * @throws Exception on exception occur
     */
    @Test
    @Ignore("flappy test")
    public void testWholeTextMessage() throws Exception {
        final TestEndpoint echoer = new TestEndpoint(new WholeStringCaptureHandler());
        Assert.assertThat(echoer, instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends Endpoint
        final Session session = container.connectToServer(echoer, serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}", session);
        session.getBasicRemote().sendText("");
        session.getBasicRemote().sendText("Echo");
        session.getBasicRemote().sendText(VERY_LONG_STRING);
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        echoer.handler.getMessageQueue().awaitMessages(2, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Method tests receiving of text messages by parts.
     *
     * @throws Exception on exception occur
     */
    @Test
    public void testPartialTextMessage() throws Exception {
        final TestEndpoint echoer = new TestEndpoint(new PartialStringCaptureHandler());
        Assert.assertThat(echoer, instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends Endpoint
        final Session session = container.connectToServer(echoer, serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}", session);
        session.getBasicRemote().sendText("");
        session.getBasicRemote().sendText("Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        echoer.handler.getMessageQueue().awaitMessages(2, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Method tests receiving of binary messages at once.
     *
     * @throws Exception on exception occur
     */
    @Test
    public void testWholeBinaryMessage() throws Exception {
        final TestEndpoint echoer = new TestEndpoint(new WholeByteBufferCaptureHandler());
        Assert.assertThat(echoer, instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends Endpoint
        final Session session = container.connectToServer(echoer, serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}", session);
        sendBinary(session, "");
        sendBinary(session, "Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        echoer.handler.getMessageQueue().awaitMessages(2, 1000, TimeUnit.MILLISECONDS);
    }

    /**
     * Method tests receiving of binary messages by parts.
     *
     * @throws Exception on exception occur
     */
    @Test
    public void testPartialBinaryMessage() throws Exception {
        final TestEndpoint echoer = new TestEndpoint(new PartialByteBufferCaptureHandler());
        Assert.assertThat(echoer, instanceOf(javax.websocket.Endpoint.class));
        // Issue connect using instance of class that extends Endpoint
        final Session session = container.connectToServer(echoer, serverUri);
        if (LOG.isDebugEnabled())
            LOG.debug("Client Connected: {}", session);
        sendBinary(session, "");
        sendBinary(session, "Echo");
        if (LOG.isDebugEnabled())
            LOG.debug("Client Message Sent");
        echoer.handler.getMessageQueue().awaitMessages(2, 1000, TimeUnit.MILLISECONDS);
    }

    private static void sendBinary(Session session, String message) throws IOException {
        final ByteBuffer bb = ByteBuffer.wrap(message.getBytes());
        session.getBasicRemote().sendBinary(bb);
    }

    private static class TestEndpoint extends Endpoint {
        public final AbstractHandler handler;

        public TestEndpoint(AbstractHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.addMessageHandler(handler);
        }

    }

    /**
     * Abstract message handler implementation, used for tests.
     */
    private static abstract class AbstractHandler implements MessageHandler {
        /**
         * Message queue to put the result messages.
         */
        private final MessageQueue messageQueue = new MessageQueue();

        /**
         * Returns message queue to test received messages count.
         *
         * @return message queue object
         */
        public MessageQueue getMessageQueue() {
            return messageQueue;
        }
    }

    /**
     * Partial message handler for receiving binary messages.
     */
    public static class PartialByteBufferCaptureHandler extends AbstractHandler implements
                    MessageHandler.Partial<ByteBuffer> {

        /**
         * Parts of the current message. This list is appended with every non-last part and is
         * cleared after last part of a message has been received.
         */
        private final List<ByteBuffer> currentMessage = new ArrayList<>();

        @Override
        public void onMessage(ByteBuffer messagePart, boolean last) {
            final ByteBuffer bufferCopy = ByteBuffer.allocate(messagePart.capacity());
            bufferCopy.put(messagePart);
            currentMessage.add(bufferCopy);
            if (last) {
                int totalSize = 0;
                for (ByteBuffer bb : currentMessage) {
                    totalSize += bb.capacity();
                }
                final ByteBuffer result = ByteBuffer.allocate(totalSize);
                for (ByteBuffer bb : currentMessage) {
                    result.put(bb);
                }
                final String stringResult = new String(result.array());
                getMessageQueue().add(stringResult);
                currentMessage.clear();
            }

        }

    }
    /**
     * Whole message handler for receiving binary messages.
     */
    public class WholeByteBufferCaptureHandler extends AbstractHandler implements
                    MessageHandler.Whole<ByteBuffer> {

        @Override
        public void onMessage(ByteBuffer message) {
            final String stringResult = new String(message.array());
            getMessageQueue().add(stringResult);

        }
    }

    /**
     * Partial message handler for receiving text messages.
     */
    public static class PartialStringCaptureHandler extends AbstractHandler implements
                    MessageHandler.Partial<String> {

        /**
         * Parts of the current message. This list is appended with every non-last part and is
         * cleared after last part of a message has been received.
         */
        private StringBuilder sb = new StringBuilder();

        @Override
        public void onMessage(String messagePart, boolean last) {
            sb.append(messagePart);
            if (last) {
                getMessageQueue().add(sb.toString());
                sb = new StringBuilder();
            }
        }

    }
    /**
     * Whole message handler for receiving text messages.
     */
    public class WholeStringCaptureHandler extends AbstractHandler implements
                    MessageHandler.Whole<String> {

        @Override
        public void onMessage(String message) {
            getMessageQueue().add(message);

        }
    }

}
