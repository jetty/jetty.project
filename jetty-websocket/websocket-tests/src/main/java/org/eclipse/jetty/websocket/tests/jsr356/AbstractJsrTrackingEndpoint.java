//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.jsr356;


import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseInfo;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

public abstract class AbstractJsrTrackingEndpoint extends Endpoint
{
    public final Logger LOG;
    
    public Session session;
    
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseInfo> closeInfo = new AtomicReference<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();
    
    public AbstractJsrTrackingEndpoint()
    {
        this("JsrTrackingEndpoint");
    }
    
    public AbstractJsrTrackingEndpoint(String id)
    {
        LOG = Log.getLogger(this.getClass().getName() + "." + id);
        LOG.debug("init");
    }
    
    public void assertCloseInfo(String prefix, int expectedCloseStatusCode, Matcher<? super String> reasonMatcher) throws InterruptedException
    {
        CloseInfo close = closeInfo.get();
        assertThat(prefix + " close info", close, Matchers.notNullValue());
        assertThat(prefix + " received close code", close.getStatusCode(), Matchers.is(expectedCloseStatusCode));
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
        assertTrue(prefix + " close event should not have occurred", closeLatch.getCount() > 0);
    }
    
    public void assertNotOpened(String prefix)
    {
        assertTrue(prefix + " open event should not have occurred", openLatch.getCount() > 0);
    }
    
    public void awaitCloseEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onClose event", closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
    
    public void awaitOpenEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onOpen event", openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
    
    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onOpen()");
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
    
    @Override
    public void onClose(Session session, CloseReason closeReason)
    {
        CloseInfo close = new CloseInfo(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
        boolean closeTracked = closeInfo.compareAndSet(null, close);
        this.closeLatch.countDown();
        assertTrue("Close only happened once", closeTracked);
    }
    
    @Override
    public void onError(Session session, Throwable cause)
    {
        assertThat("Error must have value", cause, notNullValue());
        if (error.compareAndSet(null, cause) == false)
        {
            LOG.warn("onError should only happen once - Original Cause", error.get());
            LOG.warn("onError should only happen once - Extra/Excess Cause", cause);
            fail("onError should only happen once!");
        }
    }
}
