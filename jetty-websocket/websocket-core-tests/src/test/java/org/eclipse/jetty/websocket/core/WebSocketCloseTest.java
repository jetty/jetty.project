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

package org.eclipse.jetty.websocket.core;

import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.eclipse.jetty.util.Callback.NOOP;
import static org.eclipse.jetty.websocket.core.OpCode.CLOSE;
import static org.eclipse.jetty.websocket.core.OpCode.TEXT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of a core server with a fake client
 */
public class WebSocketCloseTest extends WebSocketTester
{
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketCloseTest.class);
    private static final String WS_SCHEME = "ws";
    private static final String WSS_SCHEME = "wss";

    private WebSocketServer server;
    private DemandingTestFrameHandler serverHandler;
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

        serverHandler = new DemandingTestFrameHandler();
        server = new WebSocketServer(serverHandler, tls);

        server.start();
        client = newClient(server.getLocalPort(), tls);
        assertTrue(serverHandler.opened.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.state, containsString("CONNECTED"));
        while (!serverHandler.coreSession.toString().contains("OPEN"))
        {
            Thread.yield();
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
                serverHandler.coreSession.demand(1);
                Frame frame = serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS);
                assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));
                assertThat(serverHandler.coreSession.toString(), containsString("ISHUT"));
                LOG.info("Server: ISHUT");
                break;
            }
            case OSHUT:
            {
                serverHandler.coreSession.sendFrame(CloseStatus.toFrame(CloseStatus.NORMAL), NOOP, false);
                CloseStatus closeStatus = new CloseStatus(receiveFrame(client.getInputStream()));
                assertThat(closeStatus.getCode(), is(CloseStatus.NORMAL));
                assertThat(serverHandler.coreSession.toString(), containsString("OSHUT"));
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

        serverHandler.receivedCallback.poll().succeeded();
        Frame frame = receiveFrame(client.getInputStream());
        assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.NORMAL));

        assertTrue(serverHandler.closed.await(10, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testServerDifferentCloseISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        serverHandler.coreSession.sendFrame(CloseStatus.toFrame(CloseStatus.SHUTDOWN), NOOP, false);
        serverHandler.receivedCallback.poll().succeeded();
        Frame frame = receiveFrame(client.getInputStream());
        assertThat(new CloseStatus(frame.getPayload()).getCode(), is(CloseStatus.SHUTDOWN));

        assertTrue(serverHandler.closed.await(10, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SHUTDOWN));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testServerFailCloseISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);
        serverHandler.receivedCallback.poll().failed(new Exception("test failure"));

        CloseStatus closeStatus = new CloseStatus(receiveFrame(client.getInputStream()));
        assertThat(closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(closeStatus.getReason(), is("test failure"));

        assertTrue(serverHandler.closed.await(10, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientClosesOutputISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        client.shutdownOutput();
        assertFalse(serverHandler.closed.await(250, TimeUnit.MILLISECONDS));
        serverHandler.receivedCallback.poll().succeeded();

        CloseStatus closeStatus = new CloseStatus(receiveFrame(client.getInputStream()));
        assertThat(closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientCloseOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);
        serverHandler.coreSession.demand(1);
        client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
        assertNotNull(serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS));
        serverHandler.receivedCallback.poll().succeeded();

        assertTrue(serverHandler.closed.await(10, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.NORMAL));

        assertNull(receiveFrame(client.getInputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientDifferentCloseOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);
        serverHandler.coreSession.demand(1);
        client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.BAD_PAYLOAD), true));
        assertNotNull(serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS));
        serverHandler.receivedCallback.poll().succeeded();

        assertTrue(serverHandler.closed.await(10, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.BAD_PAYLOAD));

        assertNull(receiveFrame(client.getInputStream()));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientCloseServerFailCloseOSHUT(String scheme) throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(WebSocketCoreSession.class))
        {
            setup(State.OSHUT, scheme);
            serverHandler.coreSession.demand(1);
            client.getOutputStream().write(RawFrameBuilder.buildClose(new CloseStatus(CloseStatus.NORMAL), true));
            assertNotNull(serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS));
            serverHandler.receivedCallback.poll().failed(new Exception("Test"));

            assertTrue(serverHandler.closed.await(10, TimeUnit.SECONDS));
            assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));

            assertNull(receiveFrame(client.getInputStream()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientSendsBadFrameOPEN(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        serverHandler.coreSession.demand(1);
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));
        assertThat(serverHandler.closeStatus.getReason(), containsString("Client MUST mask all frames"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientSendsBadFrameOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        serverHandler.coreSession.demand(1);
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));
        assertThat(serverHandler.closeStatus.getReason(), containsString("Client MUST mask all frames"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientSendsBadFrameISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.PONG, "pong frame not masked", false));
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.PROTOCOL));

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
        assertFalse(serverHandler.closed.await(250, TimeUnit.MILLISECONDS));
        Callback callback = serverHandler.receivedCallback.poll(5, TimeUnit.SECONDS);

        callback.succeeded();
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.NORMAL));

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
        assertFalse(serverHandler.closed.await(250, TimeUnit.MILLISECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
        {
            while (true)
            {
                if (!serverHandler.coreSession.isOutputOpen())
                    break;
                serverHandler.coreSession.sendFrame(new Frame(TEXT, BufferUtil.toBuffer("frame after close")), Callback.NOOP, false);
                Thread.sleep(100);
            }
        });

        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertNotNull(serverHandler.error);
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));

        Callback callback = serverHandler.receivedCallback.poll(5, TimeUnit.SECONDS);
        callback.succeeded();
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientAbortsOPEN(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        client.close();
        assertFalse(serverHandler.closed.await(250, TimeUnit.MILLISECONDS));
        serverHandler.coreSession.demand(1);
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientAbortsOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);

        client.close();
        assertFalse(serverHandler.closed.await(250, TimeUnit.MILLISECONDS));
        serverHandler.coreSession.demand(1);
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.NO_CLOSE));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testClientAbortsISHUT(String scheme) throws Exception
    {
        setup(State.ISHUT, scheme);

        client.close();
        assertFalse(serverHandler.closed.await(250, TimeUnit.MILLISECONDS));
        serverHandler.coreSession.close(CloseStatus.NORMAL, "", NOOP);
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testOnFrameThrowsOPEN(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.TEXT, "throw from onFrame", true));

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketCoreSession.class))
        {
            serverHandler.coreSession.demand(1);
            assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        }

        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(serverHandler.closeStatus.getReason(), containsString("deliberately throwing from onFrame"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testOnFrameThrowsOSHUT(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);

        client.getOutputStream().write(RawFrameBuilder.buildFrame(OpCode.TEXT, "throw from onFrame", true));

        try (StacklessLogging stacklessLogging = new StacklessLogging(WebSocketCoreSession.class))
        {
            serverHandler.coreSession.demand(1);
            assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        }

        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(serverHandler.closeStatus.getReason(), containsString("deliberately throwing from onFrame"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testAbnormalCloseStatusIsHardClose(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        serverHandler.coreSession.close(CloseStatus.SERVER_ERROR, "manually sent server error", Callback.NOOP);
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(serverHandler.closeStatus.getReason(), containsString("manually sent server error"));

        Frame frame = receiveFrame(client.getInputStream());
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.SERVER_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void doubleNormalClose(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        Callback.Completable callback1 = new Callback.Completable();
        serverHandler.coreSession.close(CloseStatus.NORMAL, "normal 1", callback1);
        Callback.Completable callback2 = new Callback.Completable();
        serverHandler.coreSession.close(CloseStatus.NORMAL, "normal 2", callback2);

        // First Callback Succeeded
        assertDoesNotThrow(() -> callback1.get(5, TimeUnit.SECONDS));

        // Second Callback Failed with ClosedChannelException
        ExecutionException error = assertThrows(ExecutionException.class, () -> callback2.get(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(ClosedChannelException.class));

        // Normal close frame received on client.
        Frame closeFrame = receiveFrame(client.getInputStream());
        assertThat(closeFrame.getOpCode(), is(CLOSE));
        CloseStatus closeStatus = CloseStatus.getCloseStatus(closeFrame);
        assertThat(closeStatus.getCode(), is(CloseStatus.NORMAL));
        assertThat(closeStatus.getReason(), is("normal 1"));

        // Send close response from client.
        client.getOutputStream().write(RawFrameBuilder.buildClose(
            new CloseStatus(CloseStatus.NORMAL, "normal response 1"), true));

        serverHandler.coreSession.demand(1);
        assertNotNull(serverHandler.receivedFrames.poll(10, TimeUnit.SECONDS));
        Callback closeFrameCallback = Objects.requireNonNull(serverHandler.receivedCallback.poll());
        closeFrameCallback.succeeded();

        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.NORMAL));
        assertThat(serverHandler.closeStatus.getReason(), is("normal response 1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void doubleAbnormalClose(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        Callback.Completable callback1 = new Callback.Completable();
        serverHandler.coreSession.close(CloseStatus.SERVER_ERROR, "server error should succeed", callback1);
        Callback.Completable callback2 = new Callback.Completable();
        serverHandler.coreSession.close(CloseStatus.PROTOCOL, "protocol error should fail", callback2);

        // First Callback Succeeded
        assertDoesNotThrow(() -> callback1.get(5, TimeUnit.SECONDS));

        // Second Callback Failed with ClosedChannelException
        ExecutionException error = assertThrows(ExecutionException.class, () -> callback2.get(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(ClosedChannelException.class));

        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(serverHandler.closeStatus.getReason(), containsString("server error should succeed"));

        Frame frame = receiveFrame(client.getInputStream());
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.SERVER_ERROR));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void doubleCloseAbnormalOvertakesNormalClose(String scheme) throws Exception
    {
        setup(State.OPEN, scheme);

        Callback.Completable callback1 = new Callback.Completable();
        serverHandler.coreSession.close(CloseStatus.NORMAL, "normal close (client does not complete close handshake)", callback1);
        Callback.Completable callback2 = new Callback.Completable();
        serverHandler.coreSession.close(CloseStatus.SERVER_ERROR, "error close should overtake normal close", callback2);

        // First Callback Succeeded
        assertDoesNotThrow(() -> callback1.get(5, TimeUnit.SECONDS));

        // Second Callback Failed with ClosedChannelException
        ExecutionException error = assertThrows(ExecutionException.class, () -> callback2.get(5, TimeUnit.SECONDS));
        assertThat(error.getCause(), instanceOf(ClosedChannelException.class));

        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(serverHandler.closeStatus.getReason(), containsString("error close should overtake normal close"));

        Frame frame = receiveFrame(client.getInputStream());
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.NORMAL));
    }

    @ParameterizedTest
    @ValueSource(strings = {WS_SCHEME, WSS_SCHEME})
    public void testThrowFromOnCloseFrame(String scheme) throws Exception
    {
        setup(State.OSHUT, scheme);

        CloseStatus closeStatus = new CloseStatus(CloseStatus.NORMAL, "throw from onFrame");
        client.getOutputStream().write(RawFrameBuilder.buildClose(closeStatus, true));

        serverHandler.coreSession.demand(1);
        assertTrue(serverHandler.closed.await(5, TimeUnit.SECONDS));
        assertThat(serverHandler.closeStatus.getCode(), is(CloseStatus.SERVER_ERROR));
        assertThat(serverHandler.closeStatus.getReason(), containsString("deliberately throwing from onFrame"));
    }

    private static class DemandingTestFrameHandler implements FrameHandler
    {
        private CoreSession coreSession;
        private String state;

        protected BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
        protected BlockingQueue<Callback> receivedCallback = new BlockingArrayQueue<>();
        protected volatile Throwable error = null;
        protected CountDownLatch opened = new CountDownLatch(1);
        protected CountDownLatch closed = new CountDownLatch(1);
        protected CloseStatus closeStatus = null;

        public BlockingQueue<Frame> getFrames()
        {
            return receivedFrames;
        }

        @Override
        public void onOpen(CoreSession coreSession, Callback callback)
        {
            LOG.debug("onOpen {}", coreSession);
            this.coreSession = coreSession;
            state = this.coreSession.toString();
            opened.countDown();
            callback.succeeded();
        }

        @Override
        public void onFrame(Frame frame, Callback callback)
        {
            LOG.debug("onFrame: " + BufferUtil.toDetailString(frame.getPayload()));
            state = coreSession.toString();
            receivedCallback.offer(callback);
            receivedFrames.offer(Frame.copy(frame));

            byte opCode = frame.getOpCode();
            if ((opCode == TEXT && "throw from onFrame".equals(frame.getPayloadAsUTF8())) ||
                (opCode == CLOSE && "throw from onFrame".equals(CloseStatus.getCloseStatus(frame).getReason())))
                throw new RuntimeException("deliberately throwing from onFrame");
        }

        @Override
        public void onClosed(CloseStatus closeStatus, Callback callback)
        {
            LOG.debug("onClosed {}", closeStatus);
            state = coreSession.toString();
            this.closeStatus = closeStatus;
            closed.countDown();
            callback.succeeded();
        }

        @Override
        public void onError(Throwable cause, Callback callback)
        {
            LOG.debug("onError", cause);
            error = cause;
            state = coreSession.toString();
            callback.succeeded();
        }

        @Override
        public boolean isDemanding()
        {
            return true;
        }
    }
}
