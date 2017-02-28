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

package org.eclipse.jetty.websocket.jsr356.server;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.EncodeException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.CloseException;

@ClientEndpoint
public class EchoClientSocket
{
    private static final Logger LOG = Log.getLogger(EchoClientSocket.class);
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    private Session session;
    private Basic remote;
    private CompletableFuture<List<String>> expectedMessagesFuture;
    private AtomicInteger expectedMessageCount;
    private List<String> messages = new ArrayList<>();

    public Future<List<String>> expectedMessages(int expected)
    {
        expectedMessagesFuture = new CompletableFuture<>();
        expectedMessageCount = new AtomicInteger(expected);
        return expectedMessagesFuture;
    }
    
    public void close() throws IOException
    {
        if (session != null)
        {
            this.session.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE,"Test Complete"));
        }
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        this.session = null;
        remote = null;
        synchronized (expectedMessagesFuture)
        {
            if ((close.getCloseCode() != CloseReason.CloseCodes.NORMAL_CLOSURE) ||
                    (close.getCloseCode() != CloseReason.CloseCodes.NO_STATUS_CODE))
            {
                expectedMessagesFuture.completeExceptionally(new CloseException(close.getCloseCode().getCode(), close.getReasonPhrase()));
            }
        }
        closeLatch.countDown();
    }

    @OnError
    public void onError(Throwable t)
    {
        LOG.warn(t);
        synchronized (expectedMessagesFuture)
        {
            expectedMessagesFuture.completeExceptionally(t);
        }
    }

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
        this.remote = session.getBasicRemote();
        openLatch.countDown();
    }

    @OnMessage
    public void onText(String text) throws IOException, EncodeException
    {
        messages.add(text);
        synchronized (expectedMessagesFuture)
        {
            int countLeft = expectedMessageCount.decrementAndGet();
            if (countLeft <= 0)
            {
                expectedMessagesFuture.complete(messages);
            }
        }
    }

    public void sendText(String msg) throws IOException, EncodeException
    {
        remote.sendText(msg);
    }
    
    public void sendBinary(ByteBuffer msg) throws IOException, EncodeException
    {
        remote.sendBinary(msg);
    }

    public void sendObject(Object obj) throws IOException, EncodeException
    {
        remote.sendObject(obj);
    }

    public void sendPartialBinary(ByteBuffer part, boolean fin) throws IOException
    {
        remote.sendBinary(part,fin);
    }

    public void sendPartialText(String part, boolean fin) throws IOException
    {
        remote.sendText(part,fin);
    }
    
    public void sendPing(String message) throws IOException
    {
        remote.sendPing(BufferUtil.toBuffer(message));
    }
    
    public void sendPong(String message) throws IOException
    {
        remote.sendPong(BufferUtil.toBuffer(message));
    }
}
