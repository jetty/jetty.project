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

package org.eclipse.jetty.ee10.websocket.jakarta.common.endpoints;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import org.eclipse.jetty.ee10.websocket.jakarta.common.Defaults;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base Abstract Class.
 */
public abstract class AbstractStringEndpoint extends Endpoint implements MessageHandler.Whole<String>
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractStringEndpoint.class);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseStatus> closeInfo = new AtomicReference<>();
    protected Session session;
    protected EndpointConfig config;

    public void assertCloseInfo(String prefix, int expectedCloseStatusCode, Matcher<? super String> reasonMatcher) throws InterruptedException
    {
        CloseStatus close = closeInfo.get();
        assertThat(prefix + " close info", close, Matchers.notNullValue());
        assertThat(prefix + " received close code", close.getCode(), Matchers.is(expectedCloseStatusCode));
        assertThat(prefix + " received close reason", close.getReason(), reasonMatcher);
    }

    public void awaitCloseEvent(String prefix) throws InterruptedException
    {
        assertTrue(closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), prefix + " onClose event");
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

    @Override
    public void onClose(Session session, CloseReason closeReason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClose({}, {})", session, closeReason);
        this.session = null;
        CloseStatus close = new CloseStatus(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
        boolean closeTracked = closeInfo.compareAndSet(null, close);
        this.closeLatch.countDown();
        assertTrue(closeTracked, "Close only happened once");
    }

    @Override
    public void onError(Session session, Throwable thr)
    {
        LOG.warn("onError()", thr);
    }
}
