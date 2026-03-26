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

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestListenerEndpoint implements Session.Listener.AutoDemanding
{
    private static final Logger LOG = LoggerFactory.getLogger(TestListenerEndpoint.class);

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

    @Override
    public void onWebSocketOpen(Session session)
    {
        this.session = session;
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onOpen(): {}", this, session);
        openLatch.countDown();
    }

    @Override
    public void onWebSocketText(String message)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onWebSocketText(): {}", this, message);
        textMessages.add(message);
    }

    @Override
    public void onWebSocketBinary(ByteBuffer payload, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onWebSocketBinary(): {}", this, BufferUtil.toDetailString(payload));
        binaryMessages.add(BufferUtil.copy(payload));
        callback.succeed();
    }

    @Override
    public void onWebSocketPing(ByteBuffer payload)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onWebSocketPing(): {}", this, BufferUtil.toDetailString(payload));
        pingMessages.add(BufferUtil.copy(payload));
        session.sendPong(payload, Callback.NOOP);
    }

    @Override
    public void onWebSocketPong(ByteBuffer payload)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onWebSocketPong(): {}", this, BufferUtil.toDetailString(payload));
        pongMessages.add(BufferUtil.copy(payload));
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onWebSocketError()", this, cause);
        error = cause;
        errorLatch.countDown();
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onWebSocketClose(): {}:{}", this, statusCode, reason);
        this.closeCode = statusCode;
        this.closeReason = reason;
        closeLatch.countDown();
        callback.succeed();
    }

    @Override
    public String toString()
    {
        return String.format("[%s@%x]", TypeUtil.toShortName(getClass()), hashCode());
    }
}
