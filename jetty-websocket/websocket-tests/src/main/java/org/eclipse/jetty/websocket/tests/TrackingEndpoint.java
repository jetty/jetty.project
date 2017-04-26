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

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.WebSocketListener;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection;
import org.hamcrest.Matcher;
import org.hamcrest.Matchers;

public class TrackingEndpoint implements WebSocketListener, WebSocketFrameListener
{
    public final Logger LOG;
    
    public CountDownLatch openLatch = new CountDownLatch(1);
    
    public UpgradeRequest openUpgradeRequest;
    public UpgradeResponse openUpgradeResponse;
    
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseInfo> closeInfo = new AtomicReference<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();
    
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<WebSocketFrame> framesQueue = new LinkedBlockingDeque<>();
    
    public WebSocketSession session;
    
    public TrackingEndpoint(String id)
    {
        LOG = Log.getLogger(this.getClass().getName() + "." + id);
    }
    
    public void assertCloseInfo(String prefix, int expectedCloseStatusCode, Matcher<String> reasonMatcher) throws InterruptedException
    {
        CloseInfo close = closeInfo.get();
        assertThat(prefix + " close info", close, Matchers.notNullValue());
        assertThat(prefix + " received close code", close.getStatusCode(), Matchers.is(expectedCloseStatusCode));
        assertThat(prefix + " received close reason", close.getReason(), reasonMatcher);
    }
    
    public void assertNotOpened(String prefix)
    {
        assertTrue(prefix + " open event should not have occurred", openLatch.getCount() > 0);
    }
    
    public void assertNotClosed(String prefix)
    {
        assertTrue(prefix + " close event should not have occurred", closeLatch.getCount() > 0);
    }
    
    public void assertNoErrorEvents(String prefix)
    {
        assertTrue(prefix + " error event should not have occurred", error.get() == null);
    }
    
    public void assertErrorEvent(String prefix, Matcher<Throwable> throwableMatcher, Matcher<? super String> messageMatcher)
    {
        assertThat(prefix + " error event type", error.get(), throwableMatcher);
        assertThat(prefix + " error event message", error.get().getMessage(), messageMatcher);
    }
    
    public void awaitOpenEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onOpen event", openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
    
    public void awaitCloseEvent(String prefix) throws InterruptedException
    {
        assertTrue(prefix + " onClose event", closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
    }
    
    public void close(int statusCode, String reason)
    {
        this.session.close(statusCode, reason);
    }
    
    public RemoteEndpoint getRemote()
    {
        return session.getRemote();
    }
    
    @Override
    public void onWebSocketBinary(byte[] payload, int offset, int len)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.info("onWSBinary({})", BufferUtil.toDetailString(ByteBuffer.wrap(payload, offset, len)));
        }
        
        bufferQueue.offer(ByteBuffer.wrap(payload, offset, len));
    }
    
    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        this.closeLatch.countDown();
        CloseInfo close = new CloseInfo(statusCode, reason);
        assertThat("Close only happened once", closeInfo.compareAndSet(null, close), is(true));
    }
    
    @Override
    public void onWebSocketConnect(Session session)
    {
        assertThat("Session type", session, instanceOf(WebSocketSession.class));
        this.session = (WebSocketSession) session;
        this.openUpgradeRequest = session.getUpgradeRequest();
        this.openUpgradeResponse = session.getUpgradeResponse();
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onWebSocketConnect()");
        }
        this.openLatch.countDown();
    }
    
    @Override
    public void onWebSocketError(Throwable cause)
    {
        assertThat("Error must have value", cause, notNullValue());
        if (error.compareAndSet(null, cause) == false)
        {
            LOG.warn("onError should only happen once - Original Cause", error.get());
            LOG.warn("onError should only happen once - Extra/Excess Cause", cause);
            fail("onError should only happen once!");
        }
    }
    
    @Override
    public void onWebSocketFrame(Frame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onWSFrame({})", frame);
        }
        
        framesQueue.offer(WebSocketFrame.copy(frame));
    }
    
    @Override
    public void onWebSocketText(String text)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onWSText(\"{}\")", text);
        }
        
        messageQueue.offer(text);
    }
    
    public AbstractWebSocketConnection getConnection()
    {
        LogicalConnection connection = this.session.getConnection();
        if (connection instanceof AbstractWebSocketConnection)
        {
            return (AbstractWebSocketConnection) connection;
        }
        return null;
    }
    
    public EndPoint getJettyEndPoint()
    {
        AbstractWebSocketConnection connection = getConnection();
        if (connection != null)
        {
            return connection.getEndPoint();
        }
        return null;
    }
}
