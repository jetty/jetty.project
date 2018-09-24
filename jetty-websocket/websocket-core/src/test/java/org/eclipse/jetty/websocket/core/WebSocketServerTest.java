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

package org.eclipse.jetty.websocket.core;

import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.extensions.WebSocketExtensionRegistry;
import org.eclipse.jetty.websocket.core.server.RFC6455Handshaker;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Tests of a core server with a fake client
 */
public class WebSocketServerTest extends WebSocketTester
{
    private static Logger LOG = Log.getLogger(WebSocketServerTest.class);

    @Rule
    public TestTracker tracker = new TestTracker();
    private WebSocketServer server;

    @Test
    public void testHelloEcho() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler()
        {
            public void onReceiveFrame(Frame frame, Callback callback)
            {
                getCoreSession().sendFrame(Frame.copy(frame), Callback.NOOP, BatchMode.OFF);
                super.onReceiveFrame(frame, callback);
            }
        };

        server = new WebSocketServer(0, serverHandler);
        server.start();

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildText("Hello!", true));

            Frame frame = serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS);
            assertNotNull(frame);

            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getPayloadAsUTF8(), is("Hello!"));

            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
        }
    }

    @Test
    public void testSimpleDemand() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler()
        {
            @Override
            public boolean isDemanding()
            {
                return true;
            }
        };

        server = new WebSocketServer(0, serverHandler);
        server.start();

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildText("Hello", true));

            Frame frame = serverHandler.receivedFrames.poll(250, TimeUnit.MILLISECONDS);
            assertNull(frame);

            serverHandler.getCoreSession().demand(2);

            frame = serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getPayloadAsUTF8(), is("Hello"));

            client.getOutputStream().write(RawFrameBuilder.buildText("World", true));
            frame = serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getPayloadAsUTF8(), is("World"));

            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));

            serverHandler.getCoreSession().demand(1);
            assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
            frame = serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));

            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
        }
    }

    @Test
    public void testDemandAndRetain() throws Exception
    {
        BlockingQueue<Callback> receivedCallbacks = new BlockingArrayQueue<>();

        TestFrameHandler serverHandler = new TestFrameHandler()
        {
            @Override
            public void onOpen(CoreSession coreSession) throws Exception
            {
                super.onOpen(coreSession);
                coreSession.demand(1);
            }

            @Override
            public boolean isDemanding()
            {
                return true;
            }

            @Override
            public void onReceiveFrame(Frame frame, Callback callback)
            {
                LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
                receivedFrames.offer(frame);
                receivedCallbacks.offer(callback);
                getCoreSession().demand(1);
            }
        };

        server = new WebSocketServer(0, serverHandler);
        server.start();

        try (Socket client = newClient(server.getLocalPort()))
        {
            ByteBuffer buffer = BufferUtil.allocate(4096);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("Hello", true), 0, 6 + 5);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("Cruel", true), 0, 6 + 5);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("World", true), 0, 6 + 5);
            client.getOutputStream().write(BufferUtil.toArray(buffer));

            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (serverHandler.receivedFrames.size() < 3)
            {
                assertThat(System.nanoTime(), Matchers.lessThan(end));
                Thread.sleep(10);
            }
            assertThat(serverHandler.receivedFrames.size(), is(3));
            assertThat(receivedCallbacks.size(), is(3));

            BufferUtil.clear(buffer);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("Good", true), 0, 6 + 4);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("Bye", true), 0, 6 + 3);
            client.getOutputStream().write(BufferUtil.toArray(buffer));

            end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (serverHandler.receivedFrames.size() < 5)
            {
                assertThat(System.nanoTime(), Matchers.lessThan(end));
                Thread.sleep(10);
            }
            assertThat(serverHandler.receivedFrames.size(), is(5));
            assertThat(receivedCallbacks.size(), is(5));

            byte[] first = serverHandler.receivedFrames.poll().getPayload().array();
            assertThat(serverHandler.receivedFrames.poll().getPayload().array(), sameInstance(first));
            assertThat(serverHandler.receivedFrames.poll().getPayload().array(), sameInstance(first));
            byte[] second = serverHandler.receivedFrames.poll().getPayload().array();
            assertThat(serverHandler.receivedFrames.poll().getPayload().array(), sameInstance(second));
            assertThat(first, not(sameInstance(second)));

            ByteBufferPool pool = server.server.getConnectors()[0].getByteBufferPool();

            assertThat(pool.acquire(first.length, false).array(), not(sameInstance(first)));
            receivedCallbacks.poll().succeeded();
            assertThat(pool.acquire(first.length, false).array(), not(sameInstance(first)));
            receivedCallbacks.poll().succeeded();
            assertThat(pool.acquire(first.length, false).array(), not(sameInstance(first)));
            receivedCallbacks.poll().succeeded();
            assertThat(pool.acquire(first.length, false).array(), sameInstance(first));

            assertThat(pool.acquire(second.length, false).array(), not(sameInstance(second)));
            receivedCallbacks.poll().succeeded();
            assertThat(pool.acquire(second.length, false).array(), not(sameInstance(second)));
            receivedCallbacks.poll().succeeded();
            assertThat(pool.acquire(second.length, false).array(), sameInstance(second));
        }
    }

    @Test
    public void testBadOpCode() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame((byte)4, "payload", true));
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));

            Frame frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.PROTOCOL));
        }
    }


    @Test
    public void testNonUtf8BinaryPayload() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();

        byte[] nonUtf8Payload = {0x7F,(byte)0xFF,(byte)0xFF};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, nonUtf8Payload, true));
            Frame frame = server.handler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.BINARY));
            assertThat(frame.getPayload().array(), is(nonUtf8Payload));


            //close normally
            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
        }
    }

    @Test
    public void testValidContinuationOnNonUtf8Boundary() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();

        // Testing with 4 byte UTF8 character "\uD842\uDF9F"
        byte[] initialPayload = new byte[]{(byte)0xF0,(byte)0xA0};
        byte[] continuationPayload = new byte[]{(byte)0xAE,(byte)0x9F};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.TEXT, initialPayload, true, false));
            Frame frame = server.handler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.TEXT));
            assertThat(frame.getPayload().array(), is(initialPayload));

            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.CONTINUATION, continuationPayload, true));
            frame = server.handler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CONTINUATION));
            assertThat(frame.getPayload().array(), is(continuationPayload));

            //close normally
            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
        }
    }

    @Test
    public void testInvalidContinuationOnNonUtf8Boundary() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();

        // Testing with 4 byte UTF8 character "\uD842\uDF9F"
        byte[] initialPayload = new byte[]{(byte)0xF0,(byte)0xA0};
        byte[] incompleteContinuationPayload = new byte[]{(byte)0xAE};

        try (Socket client = newClient(server.getLocalPort()))
        {
            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.TEXT, initialPayload, true, false));
            Frame frame = server.handler.receivedFrames.poll(5, TimeUnit.SECONDS);
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.TEXT));
            assertThat(frame.getPayload().array(), is(initialPayload));

            client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.CONTINUATION, incompleteContinuationPayload, true));
            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.BAD_PAYLOAD));
        }
    }

    @Test
    public void testBadClose() throws Exception
    {
        TestFrameHandler serverHandler = new TestFrameHandler();

        server = new WebSocketServer(0, serverHandler);
        server.start();

        try (Socket client = newClient(server.getLocalPort()))
        {
            // Write client close without masking!
            client.getOutputStream().write(RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, false));
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
            Frame frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.PROTOCOL));
        }
    }

    @Test
    public void testTcpCloseNoDemand() throws Exception
    {
        BlockingQueue<Callback> receivedCallbacks = new BlockingArrayQueue<>();

        TestFrameHandler serverHandler = new TestFrameHandler()
        {
            @Override
            public void onOpen(CoreSession coreSession) throws Exception
            {
                super.onOpen(coreSession);
                coreSession.demand(3);
            }

            @Override
            public boolean isDemanding()
            {
                return true;
            }

            @Override
            public void onReceiveFrame(Frame frame, Callback callback)
            {
                LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
                receivedFrames.offer(frame);
                receivedCallbacks.offer(callback);
            }
        };

        server = new WebSocketServer(0, serverHandler);
        server.start();

        try (Socket client = newClient(server.getLocalPort()))
        {
            ByteBuffer buffer = BufferUtil.allocate(4096);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("Hello", true), 0, 6 + 5);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("Cruel", true), 0, 6 + 5);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("World", true), 0, 6 + 5);
            client.getOutputStream().write(BufferUtil.toArray(buffer));

            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (serverHandler.receivedFrames.size() < 3)
            {
                assertThat(System.nanoTime(), Matchers.lessThan(end));
                Thread.sleep(10);
            }
            assertThat(serverHandler.receivedFrames.size(), is(3));
            assertThat(receivedCallbacks.size(), is(3));

            client.close();

            assertFalse(serverHandler.closed.await(250, TimeUnit.MILLISECONDS));
            serverHandler.getCoreSession().demand(1);
            assertTrue(serverHandler.closed.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testHandlerClosed() throws Exception
    {
        BlockingQueue<Callback> receivedCallbacks = new BlockingArrayQueue<>();
        AtomicReference<CloseStatus> closedStatus = new AtomicReference<>();

        TestFrameHandler serverHandler = new TestFrameHandler()
        {
            @Override
            public void onOpen(CoreSession coreSession) throws Exception
            {
                super.onOpen(coreSession);
                coreSession.demand(2);
            }

            @Override
            public void onClosed(CloseStatus closeStatus)
            {
                closedStatus.set(closeStatus);
                super.onClosed(closeStatus);
            }

            @Override
            public boolean isDemanding()
            {
                return true;
            }

            @Override
            public void onReceiveFrame(Frame frame, Callback callback)
            {
                LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
                receivedFrames.offer(frame);
                receivedCallbacks.offer(callback);
            }
        };

        server = new WebSocketServer(0, serverHandler);
        server.start();

        try (Socket client = newClient(server.getLocalPort()))
        {
            ByteBuffer buffer = BufferUtil.allocate(4096);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("Hello", true));
            BufferUtil.append(buffer, RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            client.getOutputStream().write(BufferUtil.toArray(buffer));

            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (serverHandler.receivedFrames.size() < 2)
            {
                assertThat(System.nanoTime(), Matchers.lessThan(end));
                Thread.sleep(10);
            }
            assertThat(serverHandler.receivedFrames.size(), is(2));
            assertThat(receivedCallbacks.size(), is(2));

            assertThat(serverHandler.receivedFrames.poll().getPayloadAsUTF8(), is("Hello"));
            receivedCallbacks.poll().succeeded();

            serverHandler.getCoreSession().sendFrame(CloseStatus.toFrame(CloseStatus.SHUTDOWN, "Test Close"), Callback.NOOP, BatchMode.OFF);

            Frame frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
            assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.SHUTDOWN));

            assertTrue(serverHandler.closed.await(10, TimeUnit.SECONDS));
            assertThat(closedStatus.get().getCode(), is(CloseStatus.SHUTDOWN));

            assertThat(serverHandler.receivedFrames.poll().getOpCode(), is(OpCode.CLOSE));
            receivedCallbacks.poll().succeeded();

        }
    }

    @Test
    public void testDelayedClosed() throws Exception
    {
        BlockingQueue<Callback> receivedCallbacks = new BlockingArrayQueue<>();

        TestFrameHandler serverHandler = new TestFrameHandler()
        {
            @Override
            public void onOpen(CoreSession coreSession) throws Exception
            {
                super.onOpen(coreSession);
                coreSession.demand(2);
            }

            @Override
            public boolean isDemanding()
            {
                return true;
            }

            @Override
            public void onReceiveFrame(Frame frame, Callback callback)
            {
                LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
                receivedFrames.offer(frame);
                receivedCallbacks.offer(callback);
            }
        };

        server = new WebSocketServer(0, serverHandler);
        server.start();

        try (Socket client = newClient(server.getLocalPort()))
        {
            ByteBuffer buffer = BufferUtil.allocate(4096);
            BufferUtil.append(buffer, RawFrameBuilder.buildText("Hello", true));
            BufferUtil.append(buffer, RawFrameBuilder.buildClose(CloseStatus.NORMAL_STATUS, true));
            client.getOutputStream().write(BufferUtil.toArray(buffer));

            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (serverHandler.receivedFrames.size() < 2)
            {
                assertThat(System.nanoTime(), Matchers.lessThan(end));
                Thread.sleep(10);
            }
            assertThat(serverHandler.receivedFrames.size(), is(2));
            assertThat(receivedCallbacks.size(), is(2));

            assertThat(serverHandler.receivedFrames.poll().getPayloadAsUTF8(), is("Hello"));
            receivedCallbacks.poll().succeeded();

            serverHandler.getCoreSession().sendFrame(new Frame(OpCode.TEXT, "Ciao"), Callback.NOOP, BatchMode.OFF);

            Frame frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getPayloadAsUTF8(), is("Ciao"));

            assertThat(serverHandler.receivedFrames.poll().getOpCode(), is(OpCode.CLOSE));
            receivedCallbacks.poll().succeeded();

            frame = receiveFrame(client.getInputStream());
            assertNotNull(frame);
            assertThat(frame.getOpCode(), is(OpCode.CLOSE));
        }
    }

    static class WebSocketServer extends AbstractLifeCycle
    {
        private static Logger LOG = Log.getLogger(WebSocketServer.class);
        private final Server server;
        private final TestFrameHandler handler;

        public void doStart() throws Exception
        {
            server.start();
        }

        public void doStop() throws Exception
        {
            server.stop();
        }

        public int getLocalPort()
        {
            return server.getBean(NetworkConnector.class).getLocalPort();
        }

        public WebSocketServer(int port, TestFrameHandler frameHandler)
        {
            this.handler = frameHandler;
            server = new Server();
            server.getBean(QueuedThreadPool.class).setName("WSCoreServer");
            ServerConnector connector = new ServerConnector(server, new HttpConnectionFactory());

            connector.addBean(new WebSocketPolicy(WebSocketBehavior.SERVER));
            connector.addBean(new RFC6455Handshaker());
            connector.setPort(port);
            connector.setIdleTimeout(1000000);
            server.addConnector(connector);

            ContextHandler context = new ContextHandler("/");
            server.setHandler(context);
            WebSocketNegotiator negotiator = new TestWebSocketNegotiator(new DecoratedObjectFactory(), new WebSocketExtensionRegistry(),
                connector.getByteBufferPool(), frameHandler);

            WebSocketUpgradeHandler upgradeHandler = new TestWebSocketUpgradeHandler(negotiator);
            context.setHandler(upgradeHandler);
        }

        public void sendFrame(Frame frame)
        {
            handler.getCoreSession().sendFrame(frame, Callback.NOOP, BatchMode.AUTO);
        }

        public void sendText(String line)
        {
            LOG.info("sending {}...", line);

            handler.sendText(line);
        }

        public BlockingQueue<Frame> getFrames()
        {
            return handler.getFrames();
        }

        public void close()
        {
            handler.getCoreSession().close(CloseStatus.NORMAL, "WebSocketServer Initiated Close", Callback.NOOP);
        }

        public boolean isOpen()
        {
            return handler.getCoreSession().isOpen();
        }
    }
}
