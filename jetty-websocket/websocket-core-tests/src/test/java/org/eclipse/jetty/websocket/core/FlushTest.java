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

import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.core.server.WebSocketNegotiator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlushTest
{
    private WebSocketServer server;
    private final TestFrameHandler serverHandler = new TestFrameHandler();
    private WebSocketCoreClient client;
    private final WebSocketComponents components = new WebSocketComponents();

    @BeforeEach
    public void startup() throws Exception
    {
        WebSocketNegotiator negotiator = new TestWebSocketNegotiator(serverHandler);
        server = new WebSocketServer(negotiator);
        client = new WebSocketCoreClient(null, components);

        server.start();
        client.start();
    }

    @AfterEach
    public void shutdown() throws Exception
    {
        server.stop();
        client.stop();
    }

    @Test
    public void testStandardFlush() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CompletableFuture<CoreSession> connect = client.connect(clientHandler, server.getUri());
        connect.get(5, TimeUnit.SECONDS);

        // Send a batched frame.
        clientHandler.sendFrame(new Frame(OpCode.TEXT, "text payload"), Callback.NOOP, true);

        // We have batched the frame and not sent it.
        assertNull(serverHandler.receivedFrames.poll(1, TimeUnit.SECONDS));

        // Once we flush the frame is received.
        clientHandler.getCoreSession().flush(Callback.NOOP);
        Frame frame = Objects.requireNonNull(serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS));
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("text payload"));

        clientHandler.sendClose();
        frame = Objects.requireNonNull(serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS));
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.NO_CODE));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(clientHandler.getError());
        assertThat(clientHandler.closeStatus.getCode(), is(CloseStatus.NO_CODE));
    }

    @Test
    public void testFlushOnCloseFrame() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CompletableFuture<CoreSession> connect = client.connect(clientHandler, server.getUri());
        connect.get(5, TimeUnit.SECONDS);

        // Send a batched frame.
        clientHandler.sendFrame(new Frame(OpCode.TEXT, "text payload"), Callback.NOOP, true);

        // We have batched the frame and not sent it.
        assertNull(serverHandler.receivedFrames.poll(1, TimeUnit.SECONDS));

        // Sending the close initiates the flush and the frame is received.
        clientHandler.sendClose();
        Frame frame = Objects.requireNonNull(serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS));
        assertThat(frame.getOpCode(), is(OpCode.TEXT));
        assertThat(frame.getPayloadAsUTF8(), is("text payload"));

        frame = Objects.requireNonNull(serverHandler.receivedFrames.poll(5, TimeUnit.SECONDS));
        assertThat(CloseStatus.getCloseStatus(frame).getCode(), is(CloseStatus.NO_CODE));
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(clientHandler.getError());
        assertThat(clientHandler.closeStatus.getCode(), is(CloseStatus.NO_CODE));
    }

    @Test
    public void testFlushAfterClose() throws Exception
    {
        TestFrameHandler clientHandler = new TestFrameHandler();
        CompletableFuture<CoreSession> connect = client.connect(clientHandler, server.getUri());
        connect.get(5, TimeUnit.SECONDS);

        clientHandler.sendClose();
        assertTrue(clientHandler.closed.await(5, TimeUnit.SECONDS));
        assertNull(clientHandler.getError());

        Callback.Completable flushCallback = new Callback.Completable();
        clientHandler.getCoreSession().flush(flushCallback);
        ExecutionException e = assertThrows(ExecutionException.class, () -> flushCallback.get(5, TimeUnit.SECONDS));
        assertThat(e.getCause(), instanceOf(ClosedChannelException.class));
    }
}
