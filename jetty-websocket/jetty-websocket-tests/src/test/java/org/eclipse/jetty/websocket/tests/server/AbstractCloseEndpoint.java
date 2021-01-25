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

package org.eclipse.jetty.websocket.tests.server;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.hamcrest.Matcher;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public abstract class AbstractCloseEndpoint extends WebSocketAdapter
{
    public final Logger log;
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public String closeReason = null;
    public int closeStatusCode = -1;
    public LinkedBlockingQueue<Throwable> errors = new LinkedBlockingQueue<>();

    public AbstractCloseEndpoint()
    {
        this.log = Log.getLogger(this.getClass().getName());
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        log.debug("onWebSocketClose({}, {})", statusCode, reason);
        this.closeStatusCode = statusCode;
        this.closeReason = reason;
        closeLatch.countDown();
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        errors.offer(cause);
    }

    public void assertReceivedCloseEvent(int clientTimeoutMs, Matcher<Integer> statusCodeMatcher, Matcher<String> reasonMatcher)
        throws InterruptedException
    {
        assertThat("Client Close Event Occurred", closeLatch.await(clientTimeoutMs, TimeUnit.MILLISECONDS), is(true));
        assertThat("Client Close Event Status Code", closeStatusCode, statusCodeMatcher);
        if (reasonMatcher == null)
        {
            assertThat("Client Close Event Reason", closeReason, nullValue());
        }
        else
        {
            assertThat("Client Close Event Reason", closeReason, reasonMatcher);
        }
    }
}
