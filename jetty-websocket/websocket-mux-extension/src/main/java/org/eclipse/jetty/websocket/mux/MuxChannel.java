//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux;

import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.IOState;
import org.eclipse.jetty.websocket.common.io.IOState.ConnectionStateListener;

/**
 * MuxChannel, acts as WebSocketConnection for specific sub-channel.
 */
public class MuxChannel implements LogicalConnection, IncomingFrames, SuspendToken, ConnectionStateListener
{
    private final long channelId;
    private final Muxer muxer;
    private final AtomicBoolean suspendToken;
    private IOState ioState;
    private WebSocketPolicy policy;
    private WebSocketSession session;
    private IncomingFrames incoming;

    public MuxChannel(long channelId, Muxer muxer)
    {
        this.channelId = channelId;
        this.muxer = muxer;
        this.policy = muxer.getPolicy().clonePolicy();

        this.suspendToken = new AtomicBoolean(false);
        this.ioState = new IOState();
        this.ioState.addListener(this);
    }
    
    @Override
    public Executor getExecutor()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void close()
    {
        close(StatusCode.NORMAL,null);
    }

    @Override
    public void close(int statusCode, String reason)
    {
        CloseInfo close = new CloseInfo(statusCode,reason);
        // TODO: disconnect callback?
        outgoingFrame(close.asFrame(),null);
    }

    @Override
    public void disconnect()
    {
        // TODO: disconnect the virtual end-point?
    }

    @Override
    public ByteBufferPool getBufferPool()
    {
        // TODO Auto-generated method stub
        return null;
    }

    public long getChannelId()
    {
        return channelId;
    }

    @Override
    public long getIdleTimeout()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public IOState getIOState()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public long getMaxIdleTimeout()
    {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        return policy;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return muxer.getRemoteAddress();
    }

    @Override
    public WebSocketSession getSession()
    {
        return session;
    }

    /**
     * Incoming exceptions from Muxer.
     */
    @Override
    public void incomingError(Throwable e)
    {
        incoming.incomingError(e);
    }

    /**
     * Incoming frames from Muxer
     */
    @Override
    public void incomingFrame(Frame frame)
    {
        incoming.incomingFrame(frame);
    }

    public boolean isActive()
    {
        return (ioState.isOpen());
    }

    @Override
    public boolean isOpen()
    {
        return isActive() && muxer.isOpen();
    }

    @Override
    public boolean isReading()
    {
        return true;
    }

    public void onClose()
    {
    }

    @Override
    public void onConnectionStateChange(ConnectionState state)
    {
        // TODO Auto-generated method stub

    }

    public void onOpen()
    {
        this.ioState.onOpened();
    }

    /**
     * Frames destined for the Muxer
     */
    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
    {
        muxer.output(channelId,frame,callback);
    }

    @Override
    public void resume()
    {
        if (suspendToken.getAndSet(false))
        {
            // TODO: Start reading again. (how?)
        }
    }

    @Override
    public void setMaxIdleTimeout(long ms)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void setNextIncomingFrames(IncomingFrames incoming)
    {
        this.incoming = incoming;
    }

    @Override
    public void setSession(WebSocketSession session)
    {
        this.session = session;
        // session.setOutgoing(this);
    }

    public void setSubProtocol(String subProtocol)
    {
    }

    @Override
    public SuspendToken suspend()
    {
        suspendToken.set(true);
        // TODO: how to suspend reading?
        return this;
    }
}
