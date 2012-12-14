//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions.mux;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.SuspendToken;
import org.eclipse.jetty.websocket.api.WebSocketConnection;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.ConnectionState;
import org.eclipse.jetty.websocket.common.LogicalConnection;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.io.FutureWriteCallback;
import org.eclipse.jetty.websocket.common.io.IOState;

/**
 * MuxChannel, acts as WebSocketConnection for specific sub-channel.
 */
public class MuxChannel implements WebSocketConnection, LogicalConnection, IncomingFrames, SuspendToken
{
    private static final Logger LOG = Log.getLogger(MuxChannel.class);

    private final long channelId;
    private final Muxer muxer;
    private final AtomicBoolean inputClosed;
    private final AtomicBoolean outputClosed;
    private final AtomicBoolean suspendToken;
    private IOState ioState;
    private WebSocketPolicy policy;
    private WebSocketSession session;
    private IncomingFrames incoming;
    private String subProtocol;

    public MuxChannel(long channelId, Muxer muxer)
    {
        this.channelId = channelId;
        this.muxer = muxer;
        this.policy = muxer.getPolicy().clonePolicy();

        this.suspendToken = new AtomicBoolean(false);
        this.ioState = new IOState();
        ioState.setState(ConnectionState.CONNECTING);

        this.inputClosed = new AtomicBoolean(false);
        this.outputClosed = new AtomicBoolean(false);
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
        this.ioState.setState(ConnectionState.CLOSED);
        // TODO: disconnect the virtual end-point?
    }

    public long getChannelId()
    {
        return channelId;
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
    public URI getRequestURI()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public WebSocketSession getSession()
    {
        return session;
    }

    @Override
    public String getSubProtocol()
    {
        return this.subProtocol;
    }

    /**
     * Incoming exceptions from Muxer.
     */
    @Override
    public void incomingError(WebSocketException e)
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
        this.ioState.setState(ConnectionState.CLOSED);
    }

    public void onOpen()
    {
        this.ioState.setState(ConnectionState.OPEN);
    }

    /**
     * Internal
     * 
     * @param frame the frame to write
     * @return the future for the network write of the frame
     */
    private Future<Void> outgoingAsyncFrame(WebSocketFrame frame)
    {
        FutureWriteCallback future = new FutureWriteCallback();
        outgoingFrame(frame,future);
        return future;
    }

    /**
     * Frames destined for the Muxer
     */
    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
    {
        muxer.output(channelId,frame,callback);
    }

    /**
     * Ping frame destined for the Muxer
     */
    @Override
    public void ping(ByteBuffer buf) throws IOException
    {
        outgoingFrame(WebSocketFrame.ping().setPayload(buf),null);
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
        this.subProtocol = subProtocol;
    }

    @Override
    public SuspendToken suspend()
    {
        suspendToken.set(true);
        // TODO: how to suspend reading?
        return this;
    }

    /**
     * Generate a binary message, destined for Muxer
     */
    @Override
    public Future<Void> write(byte[] buf, int offset, int len)
    {
        ByteBuffer bb = ByteBuffer.wrap(buf,offset,len);
        return write(bb);
    }

    /**
     * Generate a binary message, destined for Muxer
     */
    @Override
    public Future<Void> write(ByteBuffer buffer)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("write with {}",BufferUtil.toDetailString(buffer));
        }
        WebSocketFrame frame = WebSocketFrame.binary().setPayload(buffer);
        return outgoingAsyncFrame(frame);
    }

    /**
     * Generate a text message, destined for Muxer
     */
    @Override
    public Future<Void> write(String message)
    {
        return outgoingAsyncFrame(WebSocketFrame.text(message));
    }
}
