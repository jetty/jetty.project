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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.core.WebSocketConnection;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.hamcrest.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class CloseTrackingEndpoint extends Session.Listener.AbstractAutoDemanding
{
    private static final Logger LOG = LoggerFactory.getLogger(CloseTrackingEndpoint.class);

    public int closeCode = -1;
    public String closeReason = null;
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicInteger closeCount = new AtomicInteger(0);
    public CountDownLatch connectLatch = new CountDownLatch(1);
    public CountDownLatch errorLatch = new CountDownLatch(1);

    public LinkedBlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
    public LinkedBlockingQueue<ByteBuffer> binaryMessageQueue = new LinkedBlockingQueue<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();

    public void assertReceivedCloseEvent(int clientTimeoutMs, Matcher<Integer> statusCodeMatcher)
        throws InterruptedException
    {
        assertReceivedCloseEvent(clientTimeoutMs, statusCodeMatcher, null);
    }

    public void assertReceivedCloseEvent(int clientTimeoutMs, Matcher<Integer> statusCodeMatcher, Matcher<String> reasonMatcher)
        throws InterruptedException
    {
        assertThat("Client Close Event Occurred", closeLatch.await(clientTimeoutMs, TimeUnit.MILLISECONDS), is(true));
        assertThat("Client Close Event Count", closeCount.get(), is(1));
        assertThat("Client Close Event Status Code", closeCode, statusCodeMatcher);
        if (reasonMatcher == null)
        {
            assertThat("Client Close Event Reason", closeReason, nullValue());
        }
        else
        {
            assertThat("Client Close Event Reason", closeReason, reasonMatcher);
        }
    }

    public void clearQueues()
    {
        messageQueue.clear();
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        LOG.debug("onWebSocketClose({},{})", statusCode, reason);
        super.onWebSocketClose(statusCode, reason);
        closeCount.incrementAndGet();
        closeCode = statusCode;
        closeReason = reason;
        closeLatch.countDown();
    }

    @Override
    public void onWebSocketOpen(Session session)
    {
        super.onWebSocketOpen(session);
        LOG.debug("onWebSocketOpen({})", session);
        connectLatch.countDown();
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        LOG.debug("onWebSocketError", cause);
        assertThat("Unique Error Event", error.compareAndSet(null, cause), is(true));
        errorLatch.countDown();
    }

    @Override
    public void onWebSocketText(String message)
    {
        LOG.debug("onWebSocketText({})", message);
        messageQueue.offer(message);
    }

    @Override
    public void onWebSocketBinary(ByteBuffer payload, Callback callback)
    {
        LOG.debug("onWebSocketBinary({})", payload.remaining());
        binaryMessageQueue.offer(BufferUtil.copy(payload));
        callback.succeed();
    }

    public EndPoint getEndPoint()
    {
        Session session = getSession();
        assertThat("Session type", session, instanceOf(WebSocketSession.class));

        WebSocketSession wsSession = (WebSocketSession)session;
        WebSocketCoreSession wsCoreSession = (WebSocketCoreSession)wsSession.getCoreSession();
        WebSocketConnection wsConnection = wsCoreSession.getConnection();

        return wsConnection.getEndPoint();
    }
}
