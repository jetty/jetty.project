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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.WebSocketFrame;

public class UntrustedWSEndpoint implements WebSocketListener, WebSocketFrameListener
{
    @SuppressWarnings("unused")
    private Session session;
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseInfo> closeInfo = new AtomicReference<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();
    
    private CompletableFuture<List<String>> expectedMessagesFuture;
    private AtomicInteger expectedMessageCount;
    private List<String> messages = new ArrayList<>();
    
    private CompletableFuture<List<WebSocketFrame>> expectedFramesFuture;
    private AtomicInteger expectedFramesCount;
    private List<WebSocketFrame> frames = new ArrayList<>();
    
    public Future<List<WebSocketFrame>> expectedFrames(int expectedCount)
    {
        expectedFramesFuture = new CompletableFuture<>();
        expectedFramesCount = new AtomicInteger(expectedCount);
        return expectedFramesFuture;
    }
    
    public Future<List<String>> expectedMessages(int expected)
    {
        expectedMessagesFuture = new CompletableFuture<>();
        expectedMessageCount = new AtomicInteger(expected);
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
            System.err.println("Original Cause");
            error.get().printStackTrace(System.err);
            System.err.println("Extra/Excess Cause");
            cause.printStackTrace(System.err);
            fail("onError should only happen once!");
        }
        
        if(expectedMessagesFuture != null)
        {
            synchronized (expectedMessagesFuture)
            {
                if (expectedMessagesFuture != null)
                    expectedMessagesFuture.completeExceptionally(cause);
            }
        }
        
        if(expectedFramesFuture != null)
        {
            synchronized (expectedFramesFuture)
            {
                if (expectedFramesFuture != null)
                    expectedFramesFuture.completeExceptionally(cause);
            }
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
        if(expectedMessagesFuture == null)
            return;
        
        messages.add(text);
        synchronized (expectedMessagesFuture)
        {
            if (expectedMessageCount.decrementAndGet() <= 0)
            {
                expectedMessagesFuture.complete(messages);
            }
        }
    }
    
    @Override
    public void onWebSocketFrame(Frame frame)
    {
        if (expectedFramesFuture == null)
            return;
        
        frames.add(WebSocketFrame.copy(frame));
        
        synchronized (expectedFramesFuture)
        {
            if (expectedFramesCount.decrementAndGet() <= 0)
            {
                expectedFramesFuture.complete(frames);
            }
        }
    }
}
