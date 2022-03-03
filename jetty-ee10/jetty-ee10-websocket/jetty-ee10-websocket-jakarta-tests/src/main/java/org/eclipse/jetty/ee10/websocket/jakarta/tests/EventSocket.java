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

package org.eclipse.jetty.ee10.websocket.jakarta.tests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.CloseReason;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint("/")
@ClientEndpoint()
public class EventSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(EventSocket.class);

    public Session session;
    public EndpointConfig endpointConfig;

    public BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
    public BlockingQueue<ByteBuffer> binaryMessages = new BlockingArrayQueue<>();
    public volatile Throwable error = null;
    public volatile CloseReason closeReason = null;

    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public CountDownLatch errorLatch = new CountDownLatch(1);

    @OnOpen
    public void onOpen(Session session, EndpointConfig endpointConfig)
    {
        this.session = session;
        this.endpointConfig = endpointConfig;
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onOpen(): {}", toString(), session);
        openLatch.countDown();
    }

    @OnMessage
    public void onMessage(String message) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onMessage(): {}", toString(), message);
        textMessages.offer(message);
    }

    @OnMessage
    public void onMessage(ByteBuffer message) throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onMessage(): {}", toString(), message);
        binaryMessages.offer(message);
    }

    @OnClose
    public void onClose(CloseReason reason)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onClose(): {}", toString(), reason);

        closeReason = reason;
        closeLatch.countDown();
    }

    @OnError
    public void onError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("{}  onError(): {}", toString(), cause);
        error = cause;
        errorLatch.countDown();
    }
}
