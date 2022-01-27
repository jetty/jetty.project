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

package org.eclipse.jetty.websocket.tests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebSocket
public class EventSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(EventSocket.class);

    public Session session;
    private String behavior;

    public BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
    public BlockingQueue<ByteBuffer> binaryMessages = new BlockingArrayQueue<>();
    public volatile int closeCode = StatusCode.UNDEFINED;
    public volatile String closeReason;
    public volatile Throwable error = null;

    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch errorLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);

    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        this.session = session;
        behavior = session.getPolicy().getBehavior().name();
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onOpen(): {}", toString(), session);
        openLatch.countDown();
    }

    @OnWebSocketMessage
    public void onMessage(String message) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onMessage(): {}", toString(), message);
        textMessages.offer(message);
    }

    @OnWebSocketMessage
    public void onMessage(byte[] buf, int offset, int len) throws IOException
    {
        ByteBuffer message = ByteBuffer.wrap(buf, offset, len);
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onMessage(): {}", toString(), message);
        binaryMessages.offer(message);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onClose(): {}:{}", toString(), statusCode, reason);
        this.closeCode = statusCode;
        this.closeReason = reason;
        closeLatch.countDown();
    }

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onError(): {}", toString(), cause);
        error = cause;
        errorLatch.countDown();
    }

    @Override
    public String toString()
    {
        return String.format("[%s@%s]", behavior, Integer.toHexString(hashCode()));
    }
}
