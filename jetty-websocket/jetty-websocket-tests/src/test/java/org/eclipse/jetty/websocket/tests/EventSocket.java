//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class EventSocket
{
    private static Logger LOG = Log.getLogger(EventSocket.class);

    public Session session;
    private String behavior;
    public volatile Throwable failure = null;
    public volatile int closeCode = -1;
    public volatile String closeReason = null;

    public BlockingQueue<String> receivedMessages = new BlockingArrayQueue<>();

    public CountDownLatch open = new CountDownLatch(1);
    public CountDownLatch error = new CountDownLatch(1);
    public CountDownLatch closed = new CountDownLatch(1);

    public Session getSession()
    {
        return session;
    }

    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        this.session = session;
        behavior = session.getPolicy().getBehavior().name();
        LOG.info("{}  onOpen(): {}", toString(), session);
        open.countDown();
    }

    @OnWebSocketMessage
    public void onMessage(String message) throws IOException
    {
        LOG.info("{}  onMessage(): {}", toString(), message);
        receivedMessages.offer(message);
    }

    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        LOG.debug("{}  onClose(): {}:{}", toString(), statusCode, reason);
        closeCode = statusCode;
        closeReason = reason;
        closed.countDown();
    }

    @OnWebSocketError
    public void onError(Throwable cause)
    {
        LOG.info("{}  onError(): {}", toString(), cause);
        failure = cause;
        error.countDown();
    }

    @Override
    public String toString()
    {
        return String.format("[%s@%s]", behavior, Integer.toHexString(hashCode()));
    }

    @WebSocket
    public static class EchoSocket extends EventSocket
    {
        @Override
        public void onMessage(String message) throws IOException
        {
            super.onMessage(message);
            session.getRemote().sendStringByFuture(message);
        }
    }
}
