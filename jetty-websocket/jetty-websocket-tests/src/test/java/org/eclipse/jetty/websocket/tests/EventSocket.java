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

package org.eclipse.jetty.websocket.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketListener;

public class EventSocket implements WebSocketListener
{
    private static Logger LOG = Log.getLogger(EventSocket.class);

    public Session session;
    private String behavior;
    public volatile Throwable failure = null;
    public volatile int closeCode = -1;
    public volatile String closeReason = null;

    public BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
    public BlockingQueue<ByteBuffer> binaryMessages = new BlockingArrayQueue<>();

    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch errorLatch = new CountDownLatch(1);

    public Session getSession()
    {
        return session;
    }

    @Override
    public void onWebSocketConnect(Session session)
    {
        this.session = session;
        behavior = session.getPolicy().getBehavior().name();
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onOpen(): {}", toString(), session);
        openLatch.countDown();
    }

    @Override
    public void onWebSocketText(String message)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onMessage(): {}", toString(), message);
        textMessages.offer(message);
    }

    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int length)
    {
        ByteBuffer message = ByteBuffer.wrap(payload, offset, length);
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onMessage(): {}", toString(), message);
        binaryMessages.offer(message);
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onClose(): {}:{}", toString(), statusCode, reason);
        closeCode = statusCode;
        closeReason = reason;
        closeLatch.countDown();
    }

    @Override
    public void onWebSocketError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onError(): {}", toString(), cause);
        failure = cause;
        errorLatch.countDown();
    }

    @Override
    public String toString()
    {
        return String.format("[%s@%s]", behavior, Integer.toHexString(hashCode()));
    }
}
