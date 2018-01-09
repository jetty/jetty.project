//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

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
        assertTrue(prefix + " error event should not have occurred", error.get() == null);
    }
    
    public void assertNotClosed(String prefix)
    {
        assertTrue(prefix + " close event should not have occurred: got " + closeInfo.get(), closeLatch.getCount() > 0);
    }
    
    public void assertNotOpened(String prefix)
    {
        assertTrue(prefix + " onOpen event should not have occurred", openLatch.getCount() > 0);
    }
    
    public void awaitCloseEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onClose event should have occurred", closeLatch.await(Timeouts.CLOSE_EVENT_MS, TimeUnit.MILLISECONDS));
    }
    
    public void awaitOpenEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onOpen event should have occurred", openLatch.await(Timeouts.OPEN_EVENT_MS, TimeUnit.MILLISECONDS));
    }

    public void awaitErrorEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onError event should have occurred", errorLatch.await(Timeouts.CLOSE_EVENT_MS, TimeUnit.MILLISECONDS));
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
        if(LOG.isDebugEnabled())
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
        if(LOG.isDebugEnabled())
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
