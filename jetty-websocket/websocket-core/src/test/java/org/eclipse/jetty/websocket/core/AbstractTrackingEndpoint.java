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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public abstract class AbstractTrackingEndpoint<T>
{
    public final Logger LOG;

    public T session;

    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch errorLatch = new CountDownLatch(1);
    public AtomicReference<CloseStatus> closeInfo = new AtomicReference<>();
    public AtomicReference<Throwable> closeStack = new AtomicReference<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();

    public AbstractTrackingEndpoint(String id)
    {
        LOG = Log.getLogger(this.getClass().getName() + "." + id);
        LOG.debug("init");
    }

    public void assertCloseInfo(String prefix, int expectedCloseStatusCode, Matcher<? super String> reasonMatcher) throws InterruptedException
    {
        CloseStatus close = closeInfo.get();
        assertThat(prefix + " close info", close, Matchers.notNullValue());
        assertThat(prefix + " received close code", close.getCode(), Matchers.is(expectedCloseStatusCode));
        assertThat(prefix + " received close reason", close.getReason(), reasonMatcher);
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
        assertTrue(closeLatch.getCount() > 0, prefix + " close event should not have occurred: got " + closeInfo.get());
    }

    public void assertNotOpened(String prefix)
    {
        assertTrue(openLatch.getCount() > 0, prefix + " onOpen event should not have occurred");
    }

    public void awaitCloseEvent(String prefix) throws InterruptedException
    {
        assertTrue(closeLatch.await(Timeouts.CLOSE_EVENT_MS, TimeUnit.MILLISECONDS), prefix + " onClose event should have occurred");
    }

    public void awaitOpenEvent(String prefix) throws InterruptedException
    {
        assertTrue(openLatch.await(Timeouts.OPEN_EVENT_MS, TimeUnit.MILLISECONDS), prefix + " onOpen event should have occurred");
    }

    public void awaitErrorEvent(String prefix) throws InterruptedException
    {
        assertTrue(errorLatch.await(Timeouts.CLOSE_EVENT_MS, TimeUnit.MILLISECONDS), prefix + " onError event should have occurred");
    }

    protected void onWSOpen(T session)
    {
        this.session = session;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onWSOpen()");
        }
        this.openLatch.countDown();
    }

    protected void onWSClose(int statusCode, String reason)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onWSClose({}, {})", statusCode, reason);
        }
        CloseStatus close = new CloseStatus(statusCode, reason);
        if (closeInfo.compareAndSet(null, close) == false)
        {
            LOG.warn("onClose should only happen once - Original Close: " + closeInfo.get(), closeStack.get());
            LOG.warn("onClose should only happen once - Extra/Excess Close: " + close, new Throwable("extra/excess"));
            fail("onClose should only happen once!");
        }
        closeStack.compareAndSet(null, new Throwable("original"));
        this.closeLatch.countDown();
    }

    protected void onWSError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onWSError()", cause);
        }
        assertThat("Error must have value", cause, notNullValue());
        if (error.compareAndSet(null, cause) == false)
        {
            LOG.warn("onError should only happen once - Original Cause", error.get());
            LOG.warn("onError should only happen once - Extra/Excess Cause", cause);
            fail("onError should only happen once!");
        }
        this.errorLatch.countDown();
    }
}
