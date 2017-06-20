//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
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

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.common.util.TextUtil;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.DataUtils;
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
    @WebSocket
    public static class SendPartialTextSocket
    {
        @OnWebSocketMessage
        public void onText(org.eclipse.jetty.websocket.api.Session session, String message) throws IOException
        {
            RemoteEndpoint remote = session.getRemote();
            String parts[] = message.split(" ");
            for (int i = 0; i < parts.length; i++)
            {
                if (i > 0)
                    remote.sendPartialString(" ", false);
                boolean last = (i >= (parts.length - 1));
                remote.sendPartialString(parts[i], last);
            }
        }
    }

    @WebSocket
    public static class SendPartialBinarySocket
    {
        @OnWebSocketMessage
        public void onText(org.eclipse.jetty.websocket.api.Session session, ByteBuffer payload) throws IOException
        {
            RemoteEndpoint remote = session.getRemote();
            ByteBuffer copy = DataUtils.copyOf(payload);
            int segmentSize = 128 * 1024;
            int segmentCount = Math.max(1, copy.remaining() / segmentSize);
            if (LOG.isDebugEnabled())
            {
                LOG.debug(".onText(payload.length={})", payload.remaining());
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
                remote.sendPartialBytes(segment.asReadOnlyBuffer(), last);
            }
        }
    }

    @WebSocket
    public static class EchoWholeMessageSocket
    {
        @OnWebSocketMessage
        public void onText(org.eclipse.jetty.websocket.api.Session session, String message) throws IOException
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.onText({})", EchoWholeMessageSocket.class.getSimpleName(), TextUtil.hint(message));
            }
            session.getRemote().sendString(message);
        }

        @OnWebSocketMessage
        public void onBinary(org.eclipse.jetty.websocket.api.Session session, ByteBuffer payload) throws IOException
        {
            if (LOG.isDebugEnabled())
            {
                LOG.debug("{}.onBinary({})", EchoWholeMessageSocket.class.getSimpleName(), BufferUtil.toDetailString(payload));
            }
            ByteBuffer copy = DataUtils.copyOf(payload);
            session.getRemote().sendBytes(copy);
        }
    }

    public static class ServerMessageSendingHandler extends WebSocketHandler implements WebSocketCreator
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.getPolicy().setMaxTextMessageSize(2 * 1024 * 1024);
            factory.getPolicy().setMaxBinaryMessageSize(2 * 1024 * 1024);
            factory.setCreator(this);
        }

        @Override
        public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp)
        {
            if (req.hasSubProtocol("partial-text"))
            {
                resp.setAcceptedSubProtocol("partial-text");
                return new SendPartialTextSocket();
            }

            if (req.hasSubProtocol("partial-binary"))
            {
                resp.setAcceptedSubProtocol("partial-binary");
                return new SendPartialBinarySocket();
            }

            if (req.hasSubProtocol("echo"))
            {
                resp.setAcceptedSubProtocol("echo");
                return new EchoWholeMessageSocket();
            }

            return null;
        }
    }

    private static final Logger LOG = Log.getLogger(MessageReceivingTest.class);
    private static Server server;
    private static URI serverUri;
    private WebSocketContainer container;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(new ServerMessageSendingHandler());
        server.setHandler(context);

        // Start Server
        server.start();

        serverUri = WSURI.toWebsocket(server.getURI()).resolve("/");
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

        final Session session = container.connectToServer(clientEndpoint, clientConfig, serverUri);
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

        final Session session = container.connectToServer(clientEndpoint, clientConfig, serverUri);
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

        final Session session = container.connectToServer(clientEndpoint, clientConfig, serverUri);
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

        final Session session = container.connectToServer(clientEndpoint, clientConfig, serverUri);
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
            if(LOG.isDebugEnabled())
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
