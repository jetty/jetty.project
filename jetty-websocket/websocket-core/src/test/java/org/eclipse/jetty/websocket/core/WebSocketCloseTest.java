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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DecoratedObjectFactory;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.internal.WebSocketChannel;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.core.server.internal.RFC6455Handshaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.eclipse.jetty.util.Callback.NOOP;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests of a core server with a fake client
 */
public class WebSocketCloseTest extends WebSocketTester
{
    private static Logger LOG = Log.getLogger(WebSocketCloseTest.class);

    private WebSocketServer server;
    private Socket client;

    enum State
    {
        OPEN, ISHUT, OSHUT
    }

    @AfterEach
    public void after() throws Exception
    {
        if (server != null)
            server.stop();
    }

    public void setup(State state) throws Exception
    {
        switch (state)
        {
            case OPEN:
            {
                TestFrameHandler serverHandler = new TestFrameHandler();
                server = new WebSocketServer(0, serverHandler);
                server.start();
                client = newClient(server.getLocalPort());

                assertTrue(server.handler.opened.await(10, TimeUnit.SECONDS));

                assertThat(server.handler.state, containsString("CONNECTED"));
                while(true)
                {
                    Thread.yield();
                    if (server.handler.getCoreSession().toString().contains("OPEN"))
                        break;
                }
                LOG.info("Server: OPEN");

                break;
            }
            case ISHUT:
            {
                TestFrameHandler serverHandler = new TestFrameHandler();

                server = new WebSocketServer(0, serverHandler);
                server.start();
                client = newClient(server.getLocalPort());

                assertTrue(server.handler.opened.await(10, TimeUnit.SECONDS));
                while(true)
                {
                    Thread.yield();
                    if (server.handler.getCoreSession().toString().contains("OPEN"))
                        break;
                }

                server.handler.getCoreSession().demand(1);
                client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
                Frame frame = serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS);
                assertNotNull(frame);
                assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));

                assertThat(server.handler.getCoreSession().toString(), containsString("ISHUT"));
                LOG.info("Server: ISHUT");

                break;
            }
            case OSHUT:
            {
                TestFrameHandler serverHandler = new TestFrameHandler();

                server = new WebSocketServer(0, serverHandler);
                server.start();
                client = newClient(server.getLocalPort());

                assertTrue(server.handler.opened.await(10, TimeUnit.SECONDS));
                while(true)
                {
                    Thread.yield();
                    if (server.handler.getCoreSession().toString().contains("OPEN"))
                        break;
                }

                server.sendFrame(CloseStatus.toFrame(CloseStatus.NORMAL));
                Frame frame = receiveFrame(client.getInputStream());
                assertNotNull(frame);
                assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));

                assertThat(server.handler.getCoreSession().toString(), containsString("OSHUT"));
                LOG.info("Server: OSHUT");

                break;
            }
        }
    }

    @Test
    public void serverClose_ISHUT() throws Exception
    {
        setup(State.ISHUT);

        server.handler.receivedCallback.poll().succeeded();
        Frame frame = receiveFrame(client.getInputStream());
        assertNotNull(frame);
        assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @Test
    public void serverDifferentClose_ISHUT() throws Exception
    {
        setup(State.ISHUT);

        server.sendFrame(CloseStatus.toFrame(CloseStatus.SHUTDOWN));
        server.handler.receivedCallback.poll().succeeded();
        Frame frame = receiveFrame(client.getInputStream());
        assertNotNull(frame);
        assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.SHUTDOWN));

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SHUTDOWN));
    }

    @Test
    public void serverFailClose_ISHUT() throws Exception
    {
        setup(State.ISHUT);
        server.handler.receivedCallback.poll().failed(new Exception("test failure"));

        Frame frame = receiveFrame(client.getInputStream());
        assertNotNull(frame);
        assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.SERVER_ERROR));

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
    }

    @Test
    public void clientClose_OSHUT() throws Exception
    {
        setup(State.OSHUT);
        server.handler.getCoreSession().demand(1);
        client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
        assertNotNull(server.handler.receivedFrames.poll(10, TimeUnit.SECONDS));
        server.handler.receivedCallback.poll().succeeded();

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));

        assertNull(receiveFrame(client.getInputStream()));
    }

    @Test
    public void clientDifferentClose_OSHUT() throws Exception
    {
        setup(State.OSHUT);
        server.handler.getCoreSession().demand(1);
        client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.BAD_PAYLOAD), true));
        assertNotNull(server.handler.receivedFrames.poll(10, TimeUnit.SECONDS));
        server.handler.receivedCallback.poll().succeeded();

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.BAD_PAYLOAD));

        assertNull(receiveFrame(client.getInputStream()));
    }

    @Test
    public void clientCloseServerFailClose_OSHUT() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(WebSocketChannel.class))
        {
            setup(State.OSHUT);
            server.handler.getCoreSession().demand(1);
            client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
            assertNotNull(server.handler.receivedFrames.poll(10, TimeUnit.SECONDS));
            server.handler.receivedCallback.poll().failed(new Exception("Test"));

            assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
            assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));

            assertNull(receiveFrame(client.getInputStream()));
        }
    }

    @Test
    public void clientSendsBadFrame_OPEN() throws Exception
    {
        setup(State.OPEN);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));
        assertThat(server.handler.closeStatus.getReason(), containsString("Client MUST mask all frames"));
    }

    @Test
    public void clientSendsBadFrame_OSHUT() throws Exception
    {
        setup(State.OSHUT);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));
        assertThat(server.handler.closeStatus.getReason(), containsString("Client MUST mask all frames"));
    }

    @Test
    public void clientSendsBadFrame_ISHUT() throws Exception
    {
        setup(State.ISHUT);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));

        server.close();
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @Test
    public void clientAborts_OPEN() throws Exception
    {
        setup(State.OPEN);

        client.close();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));
        assertThat(server.handler.closeStatus.getReason(), containsString("IOException"));
    }

    @Test
    public void clientAborts_OSHUT() throws Exception
    {
        setup(State.OSHUT);

        client.close();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));
        assertThat(server.handler.closeStatus.getReason(), containsString("IOException"));
    }

    @Test
    public void clientAborts_ISHUT() throws Exception
    {
        setup(State.ISHUT);

        client.close();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));
        server.close();
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @Test
    public void onFrameThrows_OPEN() throws Exception
    {
        setup(State.OPEN);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, "binary", true));

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketChannel.class))
        {
            server.handler.getCoreSession().demand(1);
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        }

        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(server.handler.closeStatus.getReason(), containsString("onReceiveFrame throws for binary frames"));
    }

    @Test
    public void onFrameThrows_OSHUT() throws Exception
    {
        setup(State.OSHUT);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, "binary", true));

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketChannel.class))
        {
            server.handler.getCoreSession().demand(1);
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        }

        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(server.handler.closeStatus.getReason(), containsString("onReceiveFrame throws for binary frames"));
    }

    static class TestFrameHandler implements FrameHandler.Adaptor
    {
        private CoreSession session;
        String state;

        protected BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected BlockingQueue<Callback> receivedCallback = new BlockingArrayQueue<>();
        protected CountDownLatch opened = new CountDownLatch(1);
        protected CountDownLatch closed = new CountDownLatch(1);
        protected CloseStatus closeStatus = null;

        public CoreSession getCoreSession()
        {
            return session;
        }

        public BlockingQueue<Frame> getFrames()
        {
            return receivedFrames;
        }

        @Override
        public void onOpen(CoreSession coreSession)
        {
            LOG.info("onOpen {}", coreSession);
            session = coreSession;
            state = session.toString();
            opened.countDown();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            LOG.info("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
            state = session.toString();
            receivedCallback.offer(callback);
            receivedFrames.offer(Frame.copy(frame));

            if (frame.getOpCode() == OpCode.BINARY)
                throw new IllegalArgumentException("onReceiveFrame throws for binary frames");
        }

        @Override
        public void onClosed(CloseStatus closeStatus)
        {
            LOG.info("onClosed {}", closeStatus);
            state = session.toString();
            this.closeStatus = closeStatus;
            closed.countDown();
        }

        @Override
        public void onError(Throwable cause)
        {
            LOG.info("onError {} ", cause == null?null:cause.toString());
            state = session.toString();
        }

        @Override
        public boolean isDemanding()
        {
            return true;
        }

        public void sendText(String text)
        {
            Frame frame = new Frame(OpCode.TEXT);
            frame.setFin(true);
            frame.setPayload(text);

            getCoreSession().sendFrame(frame, NOOP, false);
            state = session.toString();
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
            handler.getCoreSession().sendFrame(frame, NOOP, false);
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
            return handler.getCoreSession().isOutputOpen();
        }
    }
}
