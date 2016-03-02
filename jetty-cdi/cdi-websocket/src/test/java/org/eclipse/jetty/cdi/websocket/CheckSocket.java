//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.cdi.websocket;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

@WebSocket
public class CheckSocket extends WebSocketAdapter
{
    private static final Logger LOG = Log.getLogger(CheckSocket.class);
    private CountDownLatch closeLatch = new CountDownLatch(1);
    private CountDownLatch openLatch = new CountDownLatch(1);
    private EventQueue<String> textMessages = new EventQueue<>();

    public void awaitClose(int timeout, TimeUnit timeunit) throws InterruptedException
    {
        assertTrue("Timeout waiting for close",closeLatch.await(timeout,timeunit));
    }

    public void awaitOpen(int timeout, TimeUnit timeunit) throws InterruptedException
    {
        assertTrue("Timeout waiting for open",openLatch.await(timeout,timeunit));
    }

    public EventQueue<String> getTextMessages()
    {
        return textMessages;
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        LOG.debug("Close: {}, {}",statusCode,reason);
        super.onWebSocketClose(statusCode,reason);
        closeLatch.countDown();
    }

    @Override
    public void onWebSocketConnect(Session sess)
    {
        LOG.debug("Open: {}",sess);
        super.onWebSocketConnect(sess);
        openLatch.countDown();
    }
    
    @Override
    public void onWebSocketError(Throwable cause)
    {
        LOG.warn("WebSocket Error",cause);
        super.onWebSocketError(cause);
    }

    @Override
    public void onWebSocketText(String message)
    {
        LOG.debug("TEXT: {}",message);
        textMessages.add(message);
    }

    public void sendText(String msg) throws IOException
    {
        if (isConnected())
        {
            getRemote().sendString(msg);
        }
    }

    public void close(int statusCode, String reason)
    {
        getSession().close(statusCode,reason);
    }
}
