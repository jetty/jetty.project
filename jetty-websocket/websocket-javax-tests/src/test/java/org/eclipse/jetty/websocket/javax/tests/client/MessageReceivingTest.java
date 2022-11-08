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

package org.eclipse.jetty.websocket.javax.tests.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.util.TextUtils;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiation;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.util.AutoDemandingMessageHandler;
import org.eclipse.jetty.websocket.javax.tests.CoreServer;
import org.eclipse.jetty.websocket.javax.tests.DataUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * This class tests receiving of messages by different types of {@link javax.websocket.MessageHandler}
 */
public class MessageReceivingTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MessageReceivingTest.class);
    private static CoreServer server;
    private WebSocketContainer container;

    @BeforeAll
    public static void startServer() throws Exception
    {
        server = new CoreServer(new ServerMessageNegotiator());
        server.start();
    }

    @AfterAll
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @BeforeEach
    public void initClient()
    {
        container = ContainerProvider.getWebSocketContainer();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        ((LifeCycle)container).stop();
    }

    /**
     * Method tests receiving of binary messages by parts.
     *
     * @throws Exception on exception occur
     */
    @Test
    public void testPartialBinaryMessage() throws Exception
    {
        final TestEndpoint clientEndpoint = new TestEndpoint(new PartialByteBufferCaptureHandler());
        assertThat(clientEndpoint, instanceOf(javax.websocket.Endpoint.class));

        ClientEndpointConfig clientConfig = ClientEndpointConfig.Builder.create()
            .preferredSubprotocols(Collections.singletonList("partial-binary"))
            .build();

        try (final Session session = container.connectToServer(clientEndpoint, clientConfig, server.getWsUri()))
        {
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("", UTF_8));
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("Echo", UTF_8));
            byte[] bigBuf = new byte[1024 * 1024];
            Arrays.fill(bigBuf, (byte)'x');
            // allocate fresh ByteBuffer and copy array contents, not wrap
            // as the send will modify the wrapped array (for client masking purposes)
            ByteBuffer bigByteBuffer = ByteBuffer.allocate(bigBuf.length);
            bigByteBuffer.put(bigBuf);
            bigByteBuffer.flip();
            session.getBasicRemote().sendBinary(bigByteBuffer);
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("Another Echo", UTF_8));

            String msg;
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Echo'd Message: empty", msg, is(""));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Echo'd Message: short", msg, is("Echo"));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Echo'd Message: long.length", msg.length(), is(bigBuf.length));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Echo'd Message: another", msg, is("Another Echo"));
        }

        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }

    /**
     * Method tests receiving of text messages by parts.
     *
     * @throws Exception on exception occur
     */
    @Test
    public void testPartialTextMessage() throws Exception
    {
        final TestEndpoint clientEndpoint = new TestEndpoint(new PartialStringCaptureHandler());
        assertThat(clientEndpoint, instanceOf(javax.websocket.Endpoint.class));

        ClientEndpointConfig clientConfig = ClientEndpointConfig.Builder.create()
            .preferredSubprotocols(Collections.singletonList("partial-text"))
            .build();

        try (final Session session = container.connectToServer(clientEndpoint, clientConfig, server.getWsUri()))
        {
            session.getBasicRemote().sendText("");
            session.getBasicRemote().sendText("Echo");
            session.getBasicRemote().sendText("I can live for two months on a good compliment.");
            String msg;
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is(""));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is("Echo"));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is("I can live for two months on a good compliment."));
        }

        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }

    /**
     * Method tests receiving of binary messages at once.
     *
     * @throws Exception on exception occur
     */
    @Test
    public void testWholeBinaryMessage() throws Exception
    {
        final TestEndpoint clientEndpoint = new TestEndpoint(new WholeByteBufferCaptureHandler());
        assertThat(clientEndpoint, instanceOf(javax.websocket.Endpoint.class));

        ClientEndpointConfig clientConfig = ClientEndpointConfig.Builder.create()
            .preferredSubprotocols(Collections.singletonList("echo"))
            .build();

        try (final Session session = container.connectToServer(clientEndpoint, clientConfig, server.getWsUri()))
        {
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("", UTF_8));
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("Echo", UTF_8));

            String msg;
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is(""));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is("Echo"));
        }

        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }

    /**
     * Method tests receiving of text messages at once.
     *
     * @throws Exception on exception occur
     */
    @Test
    public void testWholeTextMessage() throws Exception
    {
        final TestEndpoint clientEndpoint = new TestEndpoint(new WholeStringCaptureHandler());
        assertThat(clientEndpoint, instanceOf(javax.websocket.Endpoint.class));

        ClientEndpointConfig clientConfig = ClientEndpointConfig.Builder.create()
            .preferredSubprotocols(Collections.singletonList("echo"))
            .build();

        byte[] raw = new byte[1024 * 1024];
        Arrays.fill(raw, (byte)'x');
        String veryLongString = new String(raw, UTF_8);

        try (final Session session = container.connectToServer(clientEndpoint, clientConfig, server.getWsUri()))
        {
            session.getBasicRemote().sendText("");
            session.getBasicRemote().sendText("Echo");
            session.getBasicRemote().sendText(veryLongString);
            session.getBasicRemote().sendText("Another Echo");

            String msg;
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message: empty", msg, is(""));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message: short", msg, is("Echo"));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message: long", msg.length(), is(veryLongString.length()));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message: another", msg, is("Another Echo"));
        }

        clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS);
    }

    public static class SendPartialTextFrameHandler extends AutoDemandingMessageHandler
    {
        @Override
        public void onText(String wholeMessage, Callback callback)
        {
            String[] parts = wholeMessage.split(" ");
            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0)
                    getCoreSession().sendFrame(new Frame(OpCode.CONTINUATION).setPayload(" ").setFin(false), Callback.NOOP, true);
                boolean last = (i >= (parts.length - 1));
                Frame frame = new Frame((i == 0) ? OpCode.TEXT : OpCode.CONTINUATION);
                frame.setPayload(BufferUtil.toBuffer(parts[i], UTF_8));
                frame.setFin(last);
                getCoreSession().sendFrame(frame, Callback.NOOP, !last);
            }
            callback.succeeded();
        }
    }

    public static class SendPartialBinaryFrameHandler extends AutoDemandingMessageHandler
    {
        @Override
        public void onBinary(ByteBuffer wholeMessage, Callback callback)
        {
            if (BufferUtil.isEmpty(wholeMessage))
            {
                getCoreSession().sendFrame(new Frame(OpCode.BINARY), Callback.NOOP, false);
                callback.succeeded();
                return;
            }

            ByteBuffer copy = DataUtils.copyOf(wholeMessage);
            int segmentSize = 128 * 1024;
            int segmentCount = Math.max(1, copy.remaining() / segmentSize);
            if (LOG.isDebugEnabled())
            {
                LOG.debug(".onWholeBinary(payload.length={}): segmentCount={}", wholeMessage.remaining(), segmentCount);
            }
            for (int i = 0; i < segmentCount; i++)
            {
                ByteBuffer segment = copy.slice();
                segment.position(i * segmentSize);
                int remaining = segment.remaining();
                segment.limit(segment.position() + Math.min(remaining, segmentSize));
                boolean last = (i >= (segmentCount - 1));
                Frame frame;
                if (i == 0)
                    frame = new Frame(OpCode.BINARY);
                else
                    frame = new Frame(OpCode.CONTINUATION);
                frame.setPayload(segment);
                frame.setFin(last);
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("segment[{}]: {}", i, frame);
                }
                getCoreSession().sendFrame(frame, Callback.NOOP, false);
            }
            callback.succeeded();
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            LOG.warn("SendPartialBinaryFrameHandler Error", cause);
            callback.succeeded();
        }
    }

    public static class EchoWholeMessageFrameHandler extends AutoDemandingMessageHandler
    {
        @Override
        public void onBinary(ByteBuffer wholeMessage, Callback callback)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.onWholeBinary({})", EchoWholeMessageFrameHandler.class.getSimpleName(), BufferUtil.toDetailString(wholeMessage));
            }

            sendBinary(wholeMessage, callback, false);
        }

        @Override
        public void onText(String wholeMessage, Callback callback)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.onWholeText({})", EchoWholeMessageFrameHandler.class.getSimpleName(), TextUtils.hint(wholeMessage));
            }

            sendText(wholeMessage, callback, false);
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            LOG.warn("EchoWholeMessageFrameHandler Error", cause);
            callback.succeeded();
        }
    }

    public static class ServerMessageNegotiator extends WebSocketNegotiator.AbstractNegotiator
    {
        private static final int MAX_MESSAGE_SIZE = (1024 * 1024) + 2;

        public ServerMessageNegotiator()
        {
            setMaxBinaryMessageSize(MAX_MESSAGE_SIZE);
            setMaxTextMessageSize(MAX_MESSAGE_SIZE);
        }

        @Override
        public FrameHandler negotiate(WebSocketNegotiation negotiation) throws IOException
        {
            List<String> offeredSubProtocols = negotiation.getOfferedSubprotocols();

            if (offeredSubProtocols.contains("partial-text"))
            {
                negotiation.setSubprotocol("partial-text");
                return new SendPartialTextFrameHandler();
            }

            if (offeredSubProtocols.contains("partial-binary"))
            {
                negotiation.setSubprotocol("partial-binary");
                SendPartialBinaryFrameHandler frameHandler = new SendPartialBinaryFrameHandler();
                return frameHandler;
            }

            if (offeredSubProtocols.contains("echo"))
            {
                negotiation.setSubprotocol("echo");
                EchoWholeMessageFrameHandler frameHandler = new EchoWholeMessageFrameHandler();
                return frameHandler;
            }

            return null;
        }
    }

    public static class TestEndpoint extends Endpoint
    {
        public final AbstractHandler handler;
        public final CountDownLatch closeLatch = new CountDownLatch(1);

        public TestEndpoint(AbstractHandler handler)
        {
            this.handler = handler;
        }

        @Override
        public void onOpen(Session session, EndpointConfig config)
        {
            session.setMaxTextMessageBufferSize(2 * 1024 * 1024);
            session.setMaxBinaryMessageBufferSize(2 * 1024 * 1024);
            session.addMessageHandler(handler);
        }

        @Override
        public void onClose(Session session, CloseReason closeReason)
        {
            closeLatch.countDown();
        }

        @Override
        public void onError(Session session, Throwable thr)
        {
            LOG.warn("TestEndpoint Error", thr);
        }
    }

    /**
     * Abstract message handler implementation, used for tests.
     */
    private abstract static class AbstractHandler implements javax.websocket.MessageHandler
    {
        public final BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    }

    /**
     * Partial message handler for receiving binary messages.
     */
    public static class PartialByteBufferCaptureHandler extends AbstractHandler implements
        javax.websocket.MessageHandler.Partial<ByteBuffer>
    {
        /**
         * Parts of the current message. This list is appended with every non-last part and is
         * cleared after last part of a message has been received.
         */
        private final List<ByteBuffer> currentMessage = new ArrayList<>();

        @Override
        public void onMessage(ByteBuffer messagePart, boolean last)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("PartialByteBufferCaptureHandler.onMessage({}, {})", BufferUtil.toDetailString(messagePart), last);
            }

            final ByteBuffer bufferCopy = DataUtils.copyOf(messagePart);
            currentMessage.add(bufferCopy);
            if (last)
            {
                int totalSize = 0;
                for (ByteBuffer bb : currentMessage)
                {
                    totalSize += bb.capacity();
                }
                final ByteBuffer result = ByteBuffer.allocate(totalSize);
                for (ByteBuffer bb : currentMessage)
                {
                    result.put(bb);
                }
                BufferUtil.flipToFlush(result, 0);
                final String stringResult = BufferUtil.toUTF8String(result);
                messageQueue.offer(stringResult);
                currentMessage.clear();
            }
        }
    }

    /**
     * Whole message handler for receiving binary messages.
     */
    public static class WholeByteBufferCaptureHandler extends AbstractHandler implements
        javax.websocket.MessageHandler.Whole<ByteBuffer>
    {
        @Override
        public void onMessage(ByteBuffer message)
        {
            final String stringResult = BufferUtil.toString(message);
            messageQueue.offer(stringResult);
        }
    }

    /**
     * Partial message handler for receiving text messages.
     */
    public static class PartialStringCaptureHandler extends AbstractHandler implements
        javax.websocket.MessageHandler.Partial<String>
    {
        /**
         * Parts of the current message. This list is appended with every non-last part and is
         * cleared after last part of a message has been received.
         */
        private StringBuilder sb = new StringBuilder();

        @Override
        public void onMessage(String messagePart, boolean last)
        {
            sb.append(messagePart);
            if (last)
            {
                messageQueue.add(sb.toString());
                sb = new StringBuilder();
            }
        }
    }

    /**
     * Whole message handler for receiving text messages.
     */
    public static class WholeStringCaptureHandler extends AbstractHandler implements
        javax.websocket.MessageHandler.Whole<String>
    {
        @Override
        public void onMessage(String message)
        {
            messageQueue.add(message);
        }
    }
}
