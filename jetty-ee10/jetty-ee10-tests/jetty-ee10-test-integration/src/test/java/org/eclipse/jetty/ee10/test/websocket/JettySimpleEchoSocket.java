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

package org.eclipse.jetty.ee10.test.websocket;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic Echo Client Socket
 */
@WebSocket
public class JettySimpleEchoSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(JettySimpleEchoSocket.class);
    private final CountDownLatch closeLatch;
    @SuppressWarnings("unused")
    private Session session;

    public JettySimpleEchoSocket()
    {
        this.closeLatch = new CountDownLatch(1);
    }

    public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException
    {
        return this.closeLatch.await(duration, unit);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        LOG.debug("Connection closed: {} - {}", statusCode, reason);
        this.session = null;
        this.closeLatch.countDown(); // trigger latch
    }

    @OnWebSocketOpen
    public void onOpen(Session session)
    {
        LOG.debug("Open: {}", session);
        this.session = session;
        session.setMaxTextMessageSize(64 * 1024);
        try
        {
            session.sendText("Foo", Callback.NOOP);
            session.close(StatusCode.NORMAL, "I'm done", Callback.NOOP);
        }
        catch (Throwable t)
        {
            LOG.warn("Unable to send string+close", t);
        }
    }

    @OnWebSocketMessage
    public void onMessage(String msg)
    {
        LOG.debug("Got msg: {}", msg);
    }
}
