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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.ContainerProvider;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.AbstractWholeMessageHandler;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.io.BatchMode;
import org.eclipse.jetty.websocket.core.server.Negotiation;
import org.eclipse.jetty.websocket.jsr356.tests.CoreServer;
import org.eclipse.jetty.websocket.jsr356.tests.DataUtils;
import org.eclipse.jetty.websocket.jsr356.util.TextUtil;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * This class tests receiving of messages by different types of {@link MessageHandler}
 */
public class MessageReceivingTest
{
    private static final Logger LOG = Log.getLogger(MessageReceivingTest.class);
    private static CoreServer server;
    private WebSocketContainer container;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new CoreServer(new ServerMessageNegotiator());
        server.start();
    }

    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }

    @Before
    public void initClient()
    {
        container = ContainerProvider.getWebSocketContainer();
    }

    @After
    public void stopClient() throws Exception
    {
        ((LifeCycle) container).stop();
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

        final Session session = container.connectToServer(clientEndpoint, clientConfig, server.getWsUri());
        try
        {
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("", UTF_8));
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("Echo", UTF_8));
            byte bigBuf[] = new byte[1024 * 1024];
            Arrays.fill(bigBuf, (byte) 'x');
            // allocate fresh ByteBuffer and copy array contents, not wrap
            // as the send will modify the wrapped array (for client masking purposes)
            ByteBuffer bigByteBuffer = ByteBuffer.allocate(bigBuf.length);
            bigByteBuffer.put(bigBuf);
            bigByteBuffer.flip();
            session.getBasicRemote().sendBinary(bigByteBuffer);
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("Another Echo", UTF_8));

            String msg;
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Echo'd Message", msg, is(""));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Echo'd Message", msg, is("Echo"));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Echo'd Message.length", msg.length(), is(bigBuf.length));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Echo'd Message", msg, is("Another Echo"));
        }
        finally
        {
            session.close();
        }
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

        final Session session = container.connectToServer(clientEndpoint, clientConfig, server.getWsUri());
        try
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
        finally
        {
            session.close();
        }
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

        final Session session = container.connectToServer(clientEndpoint, clientConfig, server.getWsUri());
        try
        {
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("", UTF_8));
            session.getBasicRemote().sendBinary(BufferUtil.toBuffer("Echo", UTF_8));

            String msg;
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is(""));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is("Echo"));
        }
        finally
        {
            session.close();
        }
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

        byte raw[] = new byte[1024 * 1024];
        Arrays.fill(raw, (byte) 'x');
        String veryLongString = new String(raw, UTF_8);

        final Session session = container.connectToServer(clientEndpoint, clientConfig, server.getWsUri());
        try
        {
            session.getBasicRemote().sendText("");
            session.getBasicRemote().sendText("Echo");
            session.getBasicRemote().sendText(veryLongString);
            session.getBasicRemote().sendText("Another Echo");

            String msg;
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is(""));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is("Echo"));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is(veryLongString));
            msg = clientEndpoint.handler.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Received Message", msg, is("Another Echo"));
        }
        finally
        {
            session.close();
        }
    }

    public static class SendPartialTextFrameHandler extends AbstractWholeMessageHandler
    {
        @Override
        public void onWholeText(String wholeMessage, Callback callback)
        {
            String parts[] = wholeMessage.split(" ");
            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0)
                    channel.sendFrame(new ContinuationFrame().setPayload(" ").setFin(false), Callback.NOOP, BatchMode.ON);
                boolean last = (i >= (parts.length - 1));
                BatchMode bm = last ? BatchMode.OFF : BatchMode.ON;
                DataFrame frame;
                if (i == 0) frame = new TextFrame();
                else frame = new ContinuationFrame();
                frame.setPayload(BufferUtil.toBuffer(parts[i], UTF_8));
                frame.setFin(last);
                channel.sendFrame(frame, Callback.NOOP, bm);
            }
            callback.succeeded();
        }
    }

    public static class SendPartialBinaryFrameHandler extends AbstractWholeMessageHandler
    {
        @Override
        public void onWholeBinary(ByteBuffer wholeMessage, Callback callback)
        {
            ByteBuffer copy = DataUtils.copyOf(wholeMessage);
            int segmentSize = 128 * 1024;
            int segmentCount = Math.max(1, copy.remaining() / segmentSize);
            if (LOG.isDebugEnabled())
            {
                LOG.debug(".onText(payload.length={})", wholeMessage.remaining());
                LOG.debug("segmentSize={}, segmentCount={}", segmentSize, segmentCount);
            }
            for (int i = 0; i < segmentCount; i++)
            {
                ByteBuffer segment = copy.slice();
                segment.position(i * segmentSize);
                int remaining = segment.remaining();
                segment.limit(segment.position() + Math.min(remaining, segmentSize));
                boolean last = (i >= (segmentCount - 1));
                if (LOG.isDebugEnabled())
                {
                    LOG.debug("segment[{}].sendPartialBytes({}, {})", i, BufferUtil.toDetailString(segment), last);
                }
                DataFrame frame;
                if (i == 0) frame = new BinaryFrame();
                else frame = new ContinuationFrame();
                frame.setPayload(segment);
                frame.setFin(last);
                channel.sendFrame(frame, Callback.NOOP, BatchMode.OFF);
            }
            callback.succeeded();
        }
    }

    public static class EchoWholeMessageFrameHandler extends AbstractWholeMessageHandler
    {
        @Override
        public void onWholeBinary(ByteBuffer wholeMessage, Callback callback)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.onWholeBinary({})", EchoWholeMessageFrameHandler.class.getSimpleName(), BufferUtil.toDetailString(wholeMessage));
            }
            channel.sendFrame(new BinaryFrame().setPayload(wholeMessage), callback, BatchMode.OFF);
        }

        @Override
        public void onWholeText(String wholeMessage, Callback callback)
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.onWholeText({})", EchoWholeMessageFrameHandler.class.getSimpleName(), TextUtil.hint(wholeMessage));
            }

            channel.sendFrame(new TextFrame().setPayload(wholeMessage), callback, BatchMode.OFF);
        }
    }

    public static class ServerMessageNegotiator extends CoreServer.BaseNegotiator
    {
        public ServerMessageNegotiator()
        {
            super();
            getCandidatePolicy().setMaxTextMessageSize(2 * 1024 * 1024);
            getCandidatePolicy().setMaxBinaryMessageSize(2 * 1024 * 1024);
        }

        @Override
        public FrameHandler negotiate(Negotiation negotiation) throws IOException
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
                return new SendPartialBinaryFrameHandler();
            }

            if (offeredSubProtocols.contains("echo"))
            {
                negotiation.setSubprotocol("echo");
                return new EchoWholeMessageFrameHandler();
            }

            return null;
        }
    }

    public static class TestEndpoint extends Endpoint
    {
        public final AbstractHandler handler;

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
    }

    /**
     * Abstract message handler implementation, used for tests.
     */
    private static abstract class AbstractHandler implements MessageHandler
    {
        public final BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    }

    /**
     * Partial message handler for receiving binary messages.
     */
    public static class PartialByteBufferCaptureHandler extends AbstractHandler implements
            MessageHandler.Partial<ByteBuffer>
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
            MessageHandler.Whole<ByteBuffer>
    {
        @Override
        public void onMessage(ByteBuffer message)
        {
            final String stringResult = new String(message.array());
            messageQueue.offer(stringResult);
        }
    }

    /**
     * Partial message handler for receiving text messages.
     */
    public static class PartialStringCaptureHandler extends AbstractHandler implements
            MessageHandler.Partial<String>
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
            MessageHandler.Whole<String>
    {
        @Override
        public void onMessage(String message)
        {
            messageQueue.add(message);
        }
    }
}
