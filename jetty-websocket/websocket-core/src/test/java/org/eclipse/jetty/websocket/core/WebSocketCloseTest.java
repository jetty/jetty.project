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

package org.eclipse.jetty.websocket.core;

import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.eclipse.jetty.websocket.core.server.WebSocketUpgradeHandler;
import org.eclipse.jetty.websocket.core.server.internal.RFC6455Handshaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.eclipse.jetty.util.Callback.NOOP;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of a core server with a fake client
 */
public class WebSocketCloseTest extends WebSocketTester
{
    private static Logger LOG = Log.getLogger(WebSocketCloseTest.class);
    private static final String WS_SCHEME = "ws";
    private static final String WSS_SCHEME = "wss";

    private WebSocketServer server;
    private Socket client;

    enum State
    {
        OPEN,
        ISHUT,
        OSHUT
    }

    @AfterEach
    public void after() throws Exception
    {
        if (server != null)
            server.stop();
    }

    public void setup(State state, String scheme) throws Exception
    {
        boolean tls;
        switch (scheme)
        {
            case "ws":
                tls = false;
                break;
            case "wss":
                tls = true;
                break;
            default:
                throw new IllegalStateException();
        }

        DemandingTestFrameHandler serverHandler = new DemandingTestFrameHandler();
        server = new WebSocketServer(0, serverHandler, tls);
        server.start();
        client = newClient(server.getLocalPort(), tls);
        assertTrue(server.handler.opened.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.state, containsString("CONNECTED"));
        while (true)
        {
            Thread.yield();
            if (server.handler.getCoreSession().toString().contains("OPEN"))
                break;
        }

        switch (state)
        {
            case OPEN:
            {
                LOG.info("Server: OPEN");
                break;
            }
            case ISHUT:
            {
                client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
                server.handler.getCoreSession().demand(1);
                Frame frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
                assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
                assertThat(server.handler.getCoreSession().toString(), containsString("ISHUT"));
                LOG.info("Server: ISHUT");
                break;
            }
            case OSHUT:
            {
                server.sendFrame(CloseStatus.toFrame(CloseStatus.NORMAL));
                CloseStatus closeStatus = new CloseStatus(receiveFrame(client.getInputStream()));
                assertThat(closeStatus.getCode(), is(CloseStatus.NORMAL));
                assertThat(server.handler.getCoreSession().toString(), containsString("OSHUT"));
                LOG.info("Server: OSHUT");
                break;
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testServerCloseISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        server.handler.receivedCallback.poll().succeeded();
        Frame frame = receiveFrame(client.getInputStream());
        assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testServerDifferentCloseISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        server.sendFrame(CloseStatus.toFrame(CloseStatus.SHUTDOWN));
        server.handler.receivedCallback.poll().succeeded();
        Frame frame = receiveFrame(client.getInputStream());
        assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.SHUTDOWN));

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SHUTDOWN));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testServerFailCloseISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);
        server.handler.receivedCallback.poll().failed(new Exception("test failure"));

        CloseStatus closeStatus = new CloseStatus(receiveFrame(client.getInputStream()));
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("test failure"));

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientClosesOutputISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        client.shutdownOutput();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));
        server.handler.receivedCallback.poll().succeeded();

        CloseStatus closeStatus = new CloseStatus(receiveFrame(client.getInputStream()));
        assertThat(closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientCloseOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);
        server.handler.getCoreSession().demand(1);
        client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
        assertNotNull(server.handler.receivedFrames.poll(10, TimeUnit.SECONDS));
        server.handler.receivedCallback.poll().succeeded();

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));

        assertNull(receiveFrame(client.getInputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientDifferentCloseOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);
        server.handler.getCoreSession().demand(1);
        client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.BAD_PAYLOAD), true));
        assertNotNull(server.handler.receivedFrames.poll(10, TimeUnit.SECONDS));
        server.handler.receivedCallback.poll().succeeded();

        assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.BAD_PAYLOAD));

        assertNull(receiveFrame(client.getInputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientCloseServerFailCloseOSHUT(String scheme) throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            setup(State.OSHUT, scheme);
            server.handler.getCoreSession().demand(1);
            client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
            assertNotNull(server.handler.receivedFrames.poll(10, TimeUnit.SECONDS));
            server.handler.receivedCallback.poll().failed(new Exception("Test"));

            assertTrue(server.handler.closed.await(10, TimeUnit.SECONDS));
            assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));

            assertNull(receiveFrame(client.getInputStream()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientSendsBadFrameOPEN(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));
        assertThat(server.handler.closeStatus.getReason(), containsString("Client MUST mask all frames"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientSendsBadFrameOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));
        assertThat(server.handler.closeStatus.getReason(), containsString("Client MUST mask all frames"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientSendsBadFrameISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));

        Frame frame = receiveFrame(client.getInputStream());
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.PROTOCOL));
        receiveEof(client.getInputStream());
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientHalfCloseISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        client.shutdownOutput();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));
        Callback callback = server.handler.receivedCallback.poll(5, TimeUnit.SECONDS);

        callback.succeeded();
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));

        Frame frame = receiveFrame(client.getInputStream());
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.NORMAL));
        receiveEof(client.getInputStream());
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientCloseServerWriteISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        client.close();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
        {
            while (true)
            {
                if (!server.isOpen())
                    break;
                server.sendFrame(new Frame(OpCode.TEXT, BufferUtil.toBuffer("frame after close")), Callback.NOOP);
                Thread.sleep(100);
            }
        });

        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertNotNull(server.handler.error);
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));

        Callback callback = server.handler.receivedCallback.poll(5, TimeUnit.SECONDS);
        callback.succeeded();
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientAbortsOPEN(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        client.close();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientAbortsOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);

        client.close();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));
        server.handler.getCoreSession().demand(1);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientAbortsISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        client.close();
        assertFalse(server.handler.closed.await(250, TimeUnit.MILLISECONDS));
        server.close();
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testOnFrameThrowsOPEN(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, "binary", true));

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketCoreSession.class))
        {
            server.handler.getCoreSession().demand(1);
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        }

        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(server.handler.closeStatus.getReason(), containsString("onReceiveFrame throws for binary frames"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testOnFrameThrowsOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.BINARY, "binary", true));

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketCoreSession.class))
        {
            server.handler.getCoreSession().demand(1);
            assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        }

        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(server.handler.closeStatus.getReason(), containsString("onReceiveFrame throws for binary frames"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testAbnormalCloseStatusIsHardClose(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        server.handler.getCoreSession().close(CloseStatus.SERVER_ERROR, "manually sent server error", Callback.NOOP);
        assertTrue(server.handler.closed.await(5, TimeUnit.SECONDS));
        assertThat(server.handler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(server.handler.closeStatus.getReason(), containsString("manually sent server error"));

        Frame frame = receiveFrame(client.getInputStream());
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.SERVER_ERROR));
    }

    static class DemandingTestFrameHandler implements SynchronousFrameHandler
    {
        private CoreSession coreSession;
        String state;

        protected BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected BlockingQueue<Callback> receivedCallback = new BlockingArrayQueue<>();
        protected volatile Throwable error = null;
        protected CountDownLatch opened = new CountDownLatch(1);
        protected CountDownLatch closed = new CountDownLatch(1);
        protected CloseStatus closeStatus = null;

        public CoreSession getCoreSession()
        {
            return coreSession;
        }

        public BlockingQueue<Frame> getFrames()
        {
            return receivedFrames;
        }

        @Override
        public void onOpen(CoreSession coreSession)
        {
            LOG.debug("onOpen {}", coreSession);
            this.coreSession = coreSession;
            state = this.coreSession.toString();
            opened.countDown();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            LOG.debug("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
            state = coreSession.toString();
            receivedCallback.offer(callback);
            receivedFrames.offer(Frame.copy(frame));

            if (frame.getOpCode() == OpCode.BINARY)
                throw new IllegalArgumentException("onReceiveFrame throws for binary frames");
        }

        @Override
        public void onClosed(CloseStatus closeStatus)
        {
            LOG.debug("onClosed {}", closeStatus);
            state = coreSession.toString();
            this.closeStatus = closeStatus;
            closed.countDown();
        }

        @Override
        public void onError(Throwable cause)
        {
            LOG.debug("onError {} ", cause);
            error = cause;
            state = coreSession.toString();
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
            state = coreSession.toString();
        }
    }

    static class WebSocketServer extends AbstractLifeCycle
    {
        private static Logger LOG = Log.getLogger(WebSocketServer.class);
        private final Server server;
        private final DemandingTestFrameHandler handler;

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

        private SslContextFactory.Server createServerSslContextFactory()
        {
            SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
            sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
            sslContextFactory.setKeyStorePassword("storepwd");
            return sslContextFactory;
        }

        public WebSocketServer(int port, DemandingTestFrameHandler frameHandler, boolean tls)
        {
            this.handler = frameHandler;
            server = new Server();
            server.getBean(QueuedThreadPool.class).setName("WSCoreServer");

            ServerConnector connector;
            if (tls)
                connector = new ServerConnector(server, createServerSslContextFactory());
            else
                connector = new ServerConnector(server);

            connector.addBean(new RFC6455Handshaker());
            connector.setPort(port);
            connector.setIdleTimeout(1000000);
            server.addConnector(connector);

            ContextHandler context = new ContextHandler("/");
            server.setHandler(context);
            WebSocketNegotiator negotiator = new TestWebSocketNegotiator(frameHandler);

            WebSocketUpgradeHandler upgradeHandler = new TestWebSocketUpgradeHandler(negotiator);
            context.setHandler(upgradeHandler);
        }

        public void sendFrame(Frame frame)
        {
            handler.getCoreSession().sendFrame(frame, NOOP, false);
        }

        public void sendFrame(Frame frame, Callback callback)
        {
            handler.getCoreSession().sendFrame(frame, callback, false);
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
