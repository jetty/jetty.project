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
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.listeners.WebSocketFrameListener;
import org.eclipse.jetty.websocket.api.listeners.WebSocketListener;
import org.eclipse.jetty.websocket.common.WSSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.frames.WSFrame;
import org.eclipse.jetty.websocket.core.handshake.UpgradeRequest;
import org.eclipse.jetty.websocket.core.handshake.UpgradeResponse;
import org.eclipse.jetty.websocket.core.io.WSConnection;

public class TrackingEndpoint extends AbstractTrackingEndpoint<WSSession> implements WebSocketListener, WebSocketFrameListener
{
    public UpgradeRequest openUpgradeRequest;
    public UpgradeResponse openUpgradeResponse;
    
    public BlockingQueue<WSFrame> framesQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();
    
    public TrackingEndpoint(String id)
    {
        super(id);
    }
    
    public void close(int statusCode, String reason)
    {
        this.session.close(statusCode, reason);
    }

    public void close(StatusCode statusCode, String reason)
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
        super.onWSClose(statusCode, reason);
    }
    
    @Override
    public void onWebSocketConnect(Session session)
    {
        assertThat("Session type", session, instanceOf(WSSession.class));
        super.onWSOpen((WSSession) session);
        this.openUpgradeRequest = session.getUpgradeRequest();
        this.openUpgradeResponse = session.getUpgradeResponse();
    }
    
    @Override
    public void onWebSocketError(Throwable cause)
    {
        super.onWSError(cause);
    }
    
    @Override
    public void onWebSocketFrame(Frame frame)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("onWSFrame({})", frame);
        }
        
        framesQueue.offer(WSFrame.copy(frame));
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
    
    public WSConnection getConnection()
    {
        return this.session.getConnection();
    }
    
    public EndPoint getJettyEndPoint()
    {
        WSConnection connection = getConnection();
        if (connection != null)
        {
            return connection.getEndPoint();
        }
        return null;
    }
}
