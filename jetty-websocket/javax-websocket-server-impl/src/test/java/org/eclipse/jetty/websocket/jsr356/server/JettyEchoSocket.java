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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.CloseException;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

/**
 * This is a Jetty API version of a websocket.
 * <p>
 * This is used a a client socket during the server tests.
 */
@WebSocket
public class JettyEchoSocket
{
    private static final Logger LOG = Log.getLogger(JettyEchoSocket.class);
    private RemoteEndpoint remote;
    private CompletableFuture<List<String>> expectedMessagesFuture;
    private AtomicInteger expectedMessageCount;
    private List<String> messages = new ArrayList<>();
    
    public Future<List<String>> expectedMessages(int expected)
    {
        expectedMessagesFuture = new CompletableFuture<>();
        expectedMessageCount = new AtomicInteger(expected);
        return expectedMessagesFuture;
    }
    
    @OnWebSocketClose
    public void onClose(int code, String reason)
    {
        remote = null;
        synchronized (expectedMessagesFuture)
        {
            if ((code != StatusCode.NORMAL) ||
                    (code != StatusCode.NO_CODE))
            {
                expectedMessagesFuture.completeExceptionally(new CloseException(code, reason));
            }
        }
    }
    
    @OnWebSocketError
    public void onError(Throwable t)
    {
        LOG.warn(t);
        synchronized (expectedMessagesFuture)
        {
            expectedMessagesFuture.completeExceptionally(t);
        }
    }
    
    @OnWebSocketMessage
    public void onMessage(String msg) throws IOException
    {
        messages.add(msg);
        synchronized (expectedMessagesFuture)
        {
            int countLeft = expectedMessageCount.decrementAndGet();
            if (countLeft <= 0)
            {
                expectedMessagesFuture.complete(messages);
            }
        }
        sendMessage(msg);
    }
    
    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        this.remote = session.getRemote();
    }
    
    public void sendMessage(String msg) throws IOException
    {
        RemoteEndpoint r = remote;
        if (r == null)
        {
            return;
        }
        
        r.sendStringByFuture(msg);
        if (r.getBatchMode() == BatchMode.ON)
            r.flush();
    }
}
