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

package org.eclipse.jetty.websocket.javax.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.hamcrest.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("unused")
public abstract class WSEventTracker
{
    public final Logger logger;

    public abstract static class Basic extends WSEventTracker
    {
        public Basic(String id)
        {
            super(id);
        }

        @OnOpen
        public void onOpen(Session session)
        {
            super.onWsOpen(session);
        }

        @OnClose
        public void onClose(CloseReason closeReason)
        {
            super.onWsClose(closeReason);
        }

        @OnError
        public void onError(Throwable cause)
        {
            super.onWsError(cause);
        }
    }

    public Session session;
    public EndpointConfig config;

    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseReason> closeDetail = new AtomicReference<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<String> events = new LinkedBlockingDeque<>();

    public WSEventTracker()
    {
        this("JsrTrackingEndpoint");
    }

    public WSEventTracker(String id)
    {
        logger = LoggerFactory.getLogger(this.getClass().getName() + "." + id);
        logger.debug("init");
    }

    public void addEvent(String format, Object... args)
    {
        events.offer(String.format(format, args));
    }

    public void assertCloseInfo(String prefix, int expectedCloseStatusCode, Matcher<? super String> reasonMatcher) throws InterruptedException
    {
        CloseReason close = closeDetail.get();
        assertThat(prefix + " close info", close, notNullValue());
        assertThat(prefix + " received close code", close.getCloseCode().getCode(), is(expectedCloseStatusCode));
        assertThat(prefix + " received close reason", close.getReasonPhrase(), reasonMatcher);
    }

    public void assertErrorEvent(String prefix, Matcher<Throwable> throwableMatcher, Matcher<? super String> messageMatcher)
    {
        assertThat(prefix + " error event type", error.get(), throwableMatcher);
        assertThat(prefix + " error event message", error.get().getMessage(), messageMatcher);
    }

    public void assertNoErrorEvents(String prefix)
    {
        assertTrue(error.get() == null, prefix + " error event should not have occurred");
    }

    public void assertNotClosed(String prefix)
    {
        assertTrue(closeLatch.getCount() > 0, prefix + " close event should not have occurred");
    }

    public void assertNotOpened(String prefix)
    {
        assertTrue(openLatch.getCount() > 0, prefix + " onOpen event should not have occurred");
    }

    public void awaitCloseEvent(String prefix) throws InterruptedException
    {
        assertTrue(closeLatch.await(Timeouts.CLOSE_EVENT_MS, TimeUnit.MILLISECONDS), prefix + " onClose event");
    }

    public void awaitOpenEvent(String prefix) throws InterruptedException
    {
        assertTrue(openLatch.await(Timeouts.OPEN_EVENT_MS, TimeUnit.MILLISECONDS), prefix + " onOpen event");
    }

    public void onWsOpen(Session session)
    {
        this.session = session;
        if (logger.isDebugEnabled())
        {
            logger.debug("onOpen({})", session);
        }
        this.openLatch.countDown();
    }

    public void onWsOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        this.config = config;
        if (logger.isDebugEnabled())
        {
            logger.debug("onOpen({}, {})", session, config);
        }
        this.openLatch.countDown();
    }

    protected void onWsText(String message)
    {
        messageQueue.offer(message);
    }

    protected void onWsBinary(ByteBuffer buffer)
    {
        ByteBuffer copy = DataUtils.copyOf(buffer);
        bufferQueue.offer(copy);
    }

    public void onWsClose(CloseReason closeReason)
    {
        boolean closeTracked = closeDetail.compareAndSet(null, closeReason);
        this.closeLatch.countDown();
        assertTrue(closeTracked, "Close only happened once");
    }

    public void onWsError(Throwable cause)
    {
        assertThat("Error must have value", cause, notNullValue());
        if (error.compareAndSet(null, cause) == false)
        {
            logger.warn("onError should only happen once - Original Cause", error.get());
            logger.warn("onError should only happen once - Extra/Excess Cause", cause);
            fail("onError should only happen once!");
        }
    }
}
