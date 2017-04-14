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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;

public class UntrustedWSEndpoint implements WebSocketListener, WebSocketFrameListener
{
    private static final Logger LOG = Log.getLogger(UntrustedWSEndpoint.class);
    
    @SuppressWarnings("unused")
    private Session session;
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseInfo> closeInfo = new AtomicReference<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();
    
    private CompletableFuture<List<String>> expectedMessagesFuture = new CompletableFuture<>();
    private AtomicReference<Integer> expectedMessageCount = new AtomicReference<>();
    private List<String> messages = new ArrayList<>();
    
    private CompletableFuture<List<WebSocketFrame>> expectedFramesFuture = new CompletableFuture<>();
    private AtomicReference<Integer> expectedFramesCount = new AtomicReference<>();
    private List<WebSocketFrame> frames = new ArrayList<>();
    
    public Future<List<WebSocketFrame>> expectedFrames(int expectedCount)
    {
        if (!expectedFramesCount.compareAndSet(null, expectedCount))
        {
            throw new IllegalStateException("Can only have 1 registered frame count future");
        }
        return expectedFramesFuture;
    }
    
    public Future<List<String>> expectedMessages(int expectedCount)
    {
        if (!expectedMessageCount.compareAndSet(null, expectedCount))
        {
            throw new IllegalStateException("Can only have 1 registered message count future");
        }
        return expectedMessagesFuture;
    }
    
    @Override
    public void onWebSocketConnect(Session session)
    {
        this.session = session;
        this.openLatch.countDown();
    }
    
    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        this.closeLatch.countDown();
        CloseInfo close = new CloseInfo(statusCode, reason);
        assertThat("Close only happened once", closeInfo.compareAndSet(null, close), is(true));
    }
    
    @Override
    public void onWebSocketError(Throwable cause)
    {
        assertThat("Error must have value", cause, notNullValue());
        if (error.compareAndSet(null, cause) == false)
        {
            LOG.warn("Original Cause", error.get());
            LOG.warn("Extra/Excess Cause", cause);
            fail("onError should only happen once!");
        }
        
        synchronized (expectedMessagesFuture)
        {
            if (expectedMessagesFuture != null)
                expectedMessagesFuture.completeExceptionally(cause);
        }
        
        synchronized (expectedFramesFuture)
        {
            if (expectedFramesFuture != null)
                expectedFramesFuture.completeExceptionally(cause);
        }
    }
    
    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        // TODO
    }
    
    @Override
    public void onWebSocketText(String text)
    {
        messages.add(text);
        synchronized (expectedMessagesFuture)
        {
            Integer expected = expectedMessageCount.get();
            
            if (expected != null && messages.size() >= expected.intValue())
            {
                expectedMessagesFuture.complete(messages);
            }
        }
    }
    
    @Override
    public void onWebSocketFrame(Frame frame)
    {
        frames.add(WebSocketFrame.copy(frame));
        synchronized (expectedFramesFuture)
        {
            Integer expected = expectedFramesCount.get();
            
            if (expected != null && frames.size() >= expected.intValue())
            {
                expectedFramesFuture.complete(frames);
            }
        }
    }
}
