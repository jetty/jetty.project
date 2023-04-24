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

package org.eclipse.jetty.websocket.tests.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.hamcrest.Matcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public abstract class AbstractCloseEndpoint extends Session.Listener.AbstractAutoDemanding
{
    public final Logger log;
    public CountDownLatch connectLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public String closeReason = null;
    public int closeStatusCode = -1;
    public LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

    public AbstractCloseEndpoint()
    {
        this.log = LoggerFactory.getLogger(this.getClass().getName());
    }

    @Override
    public void onWebSocketOpen(Session sess)
    {
        super.onWebSocketOpen(sess);
        log.debug("onWebSocketOpen({})", sess);
        connectLatch.countDown();
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        log.debug("onWebSocketError({})", cause.getClass().getSimpleName());
        errors.offer(cause);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        log.debug("onWebSocketClose({}, {})", statusCode, reason);
        this.closeStatusCode = statusCode;
        this.closeReason = reason;
        closeLatch.countDown();
    }

    public void assertReceivedCloseEvent(int clientTimeoutMs, Matcher<Integer> statusCodeMatcher, Matcher<String> reasonMatcher) throws InterruptedException
    {
        assertThat("Client Close Event Occurred", closeLatch.await(clientTimeoutMs, TimeUnit.MILLISECONDS), is(true));
        assertThat("Client Close Event Status Code", closeStatusCode, statusCodeMatcher);
        if (reasonMatcher == null)
            assertThat("Client Close Event Reason", closeReason, nullValue());
        else
            assertThat("Client Close Event Reason", closeReason, reasonMatcher);
    }
}
