//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.tests;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.hamcrest.Matcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class CloseTrackingEndpoint extends WebSocketAdapter
{
    private static final Logger LOG = Log.getLogger(CloseTrackingEndpoint.class);

    public int closeCode = -1;
    public String closeReason = null;
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicInteger closeCount = new AtomicInteger(0);
    public CountDownLatch openLatch = new CountDownLatch(1);
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
        if (reasonMatcher != null)
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
    public void onWebSocketConnect(Session session)
    {
        LOG.debug("onWebSocketConnect({})", session);
        super.onWebSocketConnect(session);
        openLatch.countDown();
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
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        LOG.debug("onWebSocketBinary({},offset,len)", payload, offset, len);
        binaryMessageQueue.offer(ByteBuffer.wrap(payload, offset, len));
    }

    public EndPoint getEndPoint() throws Exception
    {
        Session session = getSession();
        assertThat("Session type", session, instanceOf(WebSocketSession.class));

        WebSocketSession wssession = (WebSocketSession)session;
        Field fld = wssession.getClass().getDeclaredField("connection");
        fld.setAccessible(true);
        assertThat("Field: connection", fld, notNullValue());

        Object val = fld.get(wssession);
        assertThat("Connection type", val, instanceOf(AbstractWebSocketConnection.class));
        @SuppressWarnings("resource")
        AbstractWebSocketConnection wsconn = (AbstractWebSocketConnection)val;
        return wsconn.getEndPoint();
    }
}
