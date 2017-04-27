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

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

/**
 * This is a Jetty API version of a websocket.
 * <p>
 * This is used as a client socket during the server tests.
 */
@WebSocket
public class JettyEchoSocket
{
    private static final Logger LOG = Log.getLogger(JettyEchoSocket.class);
    private RemoteEndpoint remote;
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public AtomicReference<CloseInfo> closeInfo = new AtomicReference<>();
    
    @OnWebSocketClose
    public void onClose(int statusCode, String reason)
    {
        remote = null;
        CloseInfo close = new CloseInfo(statusCode, reason);
        boolean closeTracked = closeInfo.compareAndSet(null, close);
        this.closeLatch.countDown();
        assertTrue("Close only happened once", closeTracked);
    }
    
    @OnWebSocketError
    public void onError(Throwable t)
    {
        LOG.warn(t);
    }
    
    @OnWebSocketMessage
    public void onMessage(String msg) throws IOException
    {
        messageQueue.offer(msg);
        sendMessage(msg);
    }
    
    @OnWebSocketConnect
    public void onOpen(Session session)
    {
        this.remote = session.getRemote();
    }
    
    public void awaitCloseEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onClose event", closeLatch.await(5, TimeUnit.SECONDS));
    }
    
    public void assertCloseInfo(String prefix, int expectedCloseStatusCode, Matcher<? super String> reasonMatcher) throws InterruptedException
    {
        CloseInfo close = closeInfo.get();
        assertThat(prefix + " close info", close, Matchers.notNullValue());
        assertThat(prefix + " received close code", close.getStatusCode(), Matchers.is(expectedCloseStatusCode));
        assertThat(prefix + " received close reason", close.getReason(), reasonMatcher);
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
