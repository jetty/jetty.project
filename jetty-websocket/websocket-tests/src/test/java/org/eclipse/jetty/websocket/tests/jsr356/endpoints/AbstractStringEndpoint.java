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

package org.eclipse.jetty.websocket.tests.jsr356.endpoints;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.CloseInfo;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

/**
 * Base Abstract Class.
 */
public abstract class AbstractStringEndpoint extends Endpoint implements MessageHandler.Whole<String>
{
    private static final Logger LOG = Log.getLogger(AbstractStringEndpoint.class);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseInfo> closeInfo = new AtomicReference<>();
    protected Session session;
    protected EndpointConfig config;
    
    public void assertCloseInfo(String prefix, int expectedCloseStatusCode, Matcher<? super String> reasonMatcher) throws InterruptedException
    {
        CloseInfo close = closeInfo.get();
        assertThat(prefix + " close info", close, Matchers.notNullValue());
        assertThat(prefix + " received close code", close.getStatusCode(), Matchers.is(expectedCloseStatusCode));
        assertThat(prefix + " received close reason", close.getReason(), reasonMatcher);
    }
    
    public void awaitCloseEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onClose event", closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
    
    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen({}, {})", session, config);
        session.addMessageHandler(this);
        this.session = session;
        this.config = config;
    }
    
    public void onClose(Session session, CloseReason closeReason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose({}, {})", session, closeReason);
        this.session = null;
        CloseInfo close = new CloseInfo(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
        boolean closeTracked = closeInfo.compareAndSet(null, close);
        this.closeLatch.countDown();
        assertTrue("Close only happened once", closeTracked);
    }
    
    public void onError(Session session, Throwable thr)
    {
        LOG.warn("onError()", thr);
    }
}
