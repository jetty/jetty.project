//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketPing;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketPong;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebSocket
public class EventSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(EventSocket.class);

    public final BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
    public final BlockingQueue<ByteBuffer> binaryMessages = new BlockingArrayQueue<>();
    public final BlockingQueue<ByteBuffer> pongMessages = new BlockingArrayQueue<>();
    public final BlockingQueue<ByteBuffer> pingMessages = new BlockingArrayQueue<>();
    public final CountDownLatch openLatch = new CountDownLatch(1);
    public final CountDownLatch errorLatch = new CountDownLatch(1);
    public final CountDownLatch closeLatch = new CountDownLatch(1);
    public Session session;
    public int closeCode = StatusCode.UNDEFINED;
    public String closeReason;
    public Throwable error = null;

    @OnWebSocketOpen
    public void onOpen(Session session)
    {
        this.session = session;
        if (LOG.isDebugEnabled())
            LOG.debug("{} onOpen(): {}", this, session);
        openLatch.countDown();
    }

    @OnWebSocketMessage
    public void onTextMessage(String message) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onTextMessage(): {}", this, message);
        textMessages.add(message);
    }

    @OnWebSocketMessage
    public void onBinaryMessage(ByteBuffer message, Callback callback) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onBinaryMessage(): {}", this, message);
        binaryMessages.add(BufferUtil.copy(message));
        callback.succeed();
    }

    @OnWebSocketPing
    public void onPingMessage(ByteBuffer payload)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onPingMessage(): {}", this, payload);
        pingMessages.add(BufferUtil.copy(payload));
        session.sendPong(payload, Callback.NOOP);
    }

    @OnWebSocketPong
    public void onPongMessage(ByteBuffer payload)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onPongMessage(): {}", this, payload);
        pongMessages.add(BufferUtil.copy(payload));
    }

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onError()", this, cause);
        error = cause;
        errorLatch.countDown();
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{} onClose(): {}:{}", this, statusCode, reason);
        this.closeCode = statusCode;
        this.closeReason = reason;
        closeLatch.countDown();
    }

    @Override
    public String toString()
    {
        return String.format("[%s@%x]", TypeUtil.toShortName(getClass()), hashCode());
    }
}
